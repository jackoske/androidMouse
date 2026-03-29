package dev.phantom.androidmouse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

private const val TAG = "BluetoothHidManager"

@SuppressLint("MissingPermission")
class BluetoothHidManager(private val context: Context) {

    // ── Connection state ──────────────────────────────────────────────────────

    enum class State {
        IDLE,             // nothing started
        PROXY_CONNECTED,  // got BluetoothHidDevice proxy
        APP_REGISTERED,   // registerApp() succeeded; visible to host
        DEVICE_CONNECTED, // host is connected and reports can be sent
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice.asStateFlow()

    private val _pairedDevices = MutableStateFlow<Set<BluetoothDevice>>(emptySet())
    val pairedDevices: StateFlow<Set<BluetoothDevice>> = _pairedDevices.asStateFlow()

    // ── Internal ──────────────────────────────────────────────────────────────

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var hidDevice: BluetoothHidDevice? = null
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    // ── HID device callbacks ──────────────────────────────────────────────────

    private val hidCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered=$registered device=$pluggedDevice")
            if (registered) {
                _state.value = State.APP_REGISTERED
                refreshPairedDevices()
            } else {
                _state.value = State.PROXY_CONNECTED
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: ${device.name} state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectedDevice.value = device
                    _state.value = State.DEVICE_CONNECTED
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (_connectedDevice.value?.address == device.address) {
                        _connectedDevice.value = null
                    }
                    _state.value = State.APP_REGISTERED
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            // Host is asking for a report — respond with empty/current state.
            // Required by some hosts to complete enumeration.
            hidDevice?.replyReport(device, type, id, ByteArray(0))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            // Output reports from host (e.g. keyboard LED state) — ignored for now.
        }
    }

    // ── Bluetooth profile proxy listener ─────────────────────────────────────

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            Log.d(TAG, "HID Device proxy connected")
            hidDevice = proxy as BluetoothHidDevice
            _state.value = State.PROXY_CONNECTED
            registerApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            Log.d(TAG, "HID Device proxy disconnected")
            hidDevice = null
            _connectedDevice.value = null
            _state.value = State.IDLE
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Call from Activity.onStart() or ViewModel.init. */
    fun start() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Device does not support Bluetooth")
            return
        }
        if (_state.value == State.IDLE) {
            bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        }
    }

    /** Call from Activity.onStop() or ViewModel.onCleared(). */
    fun stop() {
        hidDevice?.unregisterApp()
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        hidDevice = null
        _connectedDevice.value = null
        _state.value = State.IDLE
    }

    // ── Pairing / connection ──────────────────────────────────────────────────

    /** Returns the set of BT devices already bonded to this phone. */
    fun refreshPairedDevices() {
        _pairedDevices.value = bluetoothAdapter?.bondedDevices ?: emptySet()
    }

    /** Initiate connection to a host that is already paired. */
    fun connect(device: BluetoothDevice) {
        hidDevice?.connect(device)
    }

    /** Disconnect from the current host. */
    fun disconnect() {
        _connectedDevice.value?.let { hidDevice?.disconnect(it) }
    }

    // ── HID report sending ────────────────────────────────────────────────────

    /**
     * Send a mouse movement/button/scroll report.
     * @param buttons  Bitmask: bit 0 = left, bit 1 = right, bit 2 = middle
     * @param dx       Relative X movement (-127..127)
     * @param dy       Relative Y movement (-127..127)
     * @param wheel    Scroll wheel delta (-127..127), negative = scroll down
     */
    fun sendMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val device = _connectedDevice.value ?: return
        val data = byteArrayOf(
            (buttons and 0x07).toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte(),
        )
        hidDevice?.sendReport(device, HidConstants.REPORT_ID_MOUSE, data)
    }

    /**
     * Send a keyboard report.
     * @param modifiers  Bitmask of modifier keys (see HidConstants.MOD_*)
     * @param keycodes   Up to 6 HID keycodes pressed simultaneously
     */
    fun sendKeyboardReport(modifiers: Int, keycodes: IntArray) {
        val device = _connectedDevice.value ?: return
        val data = ByteArray(8)
        data[0] = modifiers.toByte()
        data[1] = 0 // reserved
        keycodes.take(6).forEachIndexed { i, key -> data[i + 2] = key.toByte() }
        hidDevice?.sendReport(device, HidConstants.REPORT_ID_KEYBOARD, data)
    }

    /** Release all keys and mouse buttons. */
    fun releaseAll() {
        sendMouseReport(0, 0, 0, 0)
        sendKeyboardReport(0, intArrayOf())
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun registerApp() {
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Android Mouse",                       // name shown during pairing
            "Android BT HID Controller",           // description
            "AndroidMouse",                        // provider
            BluetoothHidDevice.SUBCLASS1_COMBO,    // mouse + keyboard combo
            HidConstants.DESCRIPTOR,
        )
        hidDevice?.registerApp(sdpSettings, null, null, callbackExecutor, hidCallback)
    }
}
