package com.example.androidmouse

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HidViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("androidmouse", Context.MODE_PRIVATE)

    // ── Gyro / Air Mouse ────────────────────────────────────────────────────────

    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val _airMouseActive = MutableStateFlow(false)
    val airMouseActive: StateFlow<Boolean> = _airMouseActive.asStateFlow()

    private val _airMouseAvailable = MutableStateFlow(rotationSensor != null)
    val airMouseAvailable: StateFlow<Boolean> = _airMouseAvailable.asStateFlow()

    private var refRotationMatrix: FloatArray? = null
    private val currentRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var lastYaw = 0f
    private var lastPitch = 0f

    private val airMouseSensitivityScale = 800f // radians → pixels scale factor

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!_airMouseActive.value || !connected) return

            SensorManager.getRotationMatrixFromVector(currentRotationMatrix, event.values)

            val ref = refRotationMatrix
            if (ref == null) {
                refRotationMatrix = currentRotationMatrix.copyOf()
                SensorManager.getOrientation(currentRotationMatrix, orientationAngles)
                lastYaw = orientationAngles[0]
                lastPitch = orientationAngles[1]
                return
            }

            SensorManager.getOrientation(currentRotationMatrix, orientationAngles)
            val yaw = orientationAngles[0]
            val pitch = orientationAngles[1]

            var deltaYaw = yaw - lastYaw
            var deltaPitch = pitch - lastPitch

            // Handle yaw wraparound (-PI to PI)
            if (deltaYaw > Math.PI) deltaYaw -= (2 * Math.PI).toFloat()
            if (deltaYaw < -Math.PI) deltaYaw += (2 * Math.PI).toFloat()

            lastYaw = yaw
            lastPitch = pitch

            val s = _sensitivity.value
            val dx = (deltaYaw * airMouseSensitivityScale * s).toInt()
            val dy = (deltaPitch * airMouseSensitivityScale * s).toInt()

            if (dx != 0 || dy != 0) network.sendMouseReport(0, dx, dy, 0)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun startAirMouse() {
        if (rotationSensor == null) return
        refRotationMatrix = null
        sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        _airMouseActive.value = true
    }

    fun stopAirMouse() {
        _airMouseActive.value = false
        sensorManager.unregisterListener(sensorListener)
        refRotationMatrix = null
    }

    fun recenterAirMouse() {
        refRotationMatrix = null
    }

    val network = NetworkHidManager()
    val netState: StateFlow<NetworkHidManager.State> = network.state

    // ── mDNS discovery ────────────────────────────────────────────────────────
    val discovery = ServerDiscovery(app)

    // ── Settings ──────────────────────────────────────────────────────────────

    private val _sensitivity = MutableStateFlow(prefs.getFloat("sensitivity", 1.5f))
    val sensitivity: StateFlow<Float> = _sensitivity.asStateFlow()

    private val _invertScroll = MutableStateFlow(prefs.getBoolean("invertScroll", false))
    val invertScroll: StateFlow<Boolean> = _invertScroll.asStateFlow()

    fun setSensitivity(v: Float) {
        _sensitivity.value = v
        prefs.edit().putFloat("sensitivity", v).apply()
    }

    fun setInvertScroll(v: Boolean) {
        _invertScroll.value = v
        prefs.edit().putBoolean("invertScroll", v).apply()
    }

    // ── Saved connection ────────────────────────────────────────────────────

    val savedHost: String get() = prefs.getString("lastHost", "") ?: ""
    val savedPort: String get() = prefs.getString("lastPort", "9393") ?: "9393"

    private fun saveConnection(host: String, port: Int) {
        prefs.edit()
            .putString("lastHost", host)
            .putString("lastPort", port.toString())
            .apply()
    }

    // ── Modifier keys (toggled by UI) ─────────────────────────────────────────

    private val _activeModifiers = MutableStateFlow(0)
    val activeModifiers: StateFlow<Int> = _activeModifiers.asStateFlow()

    fun toggleModifier(mod: Int) {
        _activeModifiers.value = _activeModifiers.value xor mod
    }

    fun clearModifiers() {
        _activeModifiers.value = 0
    }

    // ── Connection ────────────────────────────────────────────────────────────

    val connected get() = netState.value == NetworkHidManager.State.CONNECTED

    init {
        // Watch for disconnection and auto-clear modifiers
        viewModelScope.launch {
            var wasConnected = false
            netState.collect { state ->
                if (wasConnected && state == NetworkHidManager.State.DISCONNECTED) {
                    clearModifiers()
                    stopAirMouse()
                }
                wasConnected = state == NetworkHidManager.State.CONNECTED
            }
        }
    }

    fun connectNetwork(host: String, port: Int = 9393) {
        saveConnection(host, port)
        network.connect(host, port, viewModelScope)
    }

    fun disconnectNetwork() {
        clearModifiers()
        network.disconnect()
    }

    // ── Mouse ─────────────────────────────────────────────────────────────────

    fun moveMouse(rawDx: Float, rawDy: Float) {
        val s = _sensitivity.value
        val dx = (rawDx * s).toInt()
        val dy = (rawDy * s).toInt()
        if (dx != 0 || dy != 0) network.sendMouseReport(0, dx, dy, 0)
    }

    fun scroll(rawDy: Float) {
        val sign = if (_invertScroll.value) 1 else -1
        val delta = (sign * rawDy * 0.25f).toInt()
        if (delta != 0) network.sendMouseReport(0, 0, 0, delta)
    }

    fun clickDown(button: Int) = network.sendMouseReport(button, 0, 0, 0)
    fun clickUp()              = network.sendMouseReport(0, 0, 0, 0)

    // ── Keyboard ──────────────────────────────────────────────────────────────

    fun typeChar(char: Char) {
        val (keycode, charMods) = HidConstants.charToHid(char) ?: return
        val mods = charMods or _activeModifiers.value
        network.sendKeyboardReport(mods, intArrayOf(keycode))
        network.sendKeyboardReport(_activeModifiers.value, intArrayOf())
    }

    fun pressEnter() {
        network.sendKeyboardReport(_activeModifiers.value, intArrayOf(0x28))
        network.sendKeyboardReport(_activeModifiers.value, intArrayOf())
    }

    fun pressBackspace() {
        network.sendKeyboardReport(_activeModifiers.value, intArrayOf(0x2A))
        network.sendKeyboardReport(_activeModifiers.value, intArrayOf())
    }

    fun pressEscape() {
        network.sendKeyboardReport(_activeModifiers.value, intArrayOf(0x29))
        network.sendKeyboardReport(_activeModifiers.value, intArrayOf())
    }

    fun pressTab() {
        network.sendKeyboardReport(_activeModifiers.value, intArrayOf(0x2B))
        network.sendKeyboardReport(_activeModifiers.value, intArrayOf())
    }

    override fun onCleared() {
        stopAirMouse()
        discovery.stopDiscovery()
        network.disconnect()
        super.onCleared()
    }
}
