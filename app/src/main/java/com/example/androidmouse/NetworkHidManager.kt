package com.example.androidmouse

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "NetworkHidManager"

class NetworkHidManager {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    // Outbound packet queue — UI thread enqueues, IO coroutine drains to socket.
    // DROP_OLDEST so a lagging connection never blocks touch events.
    private val sendChannel = Channel<ByteArray>(capacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    private var job: Job? = null
    private var socket: Socket? = null

    // Last connection target for reconnect
    var lastHost: String? = null
        private set
    var lastPort: Int = 9393
        private set

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun connect(host: String, port: Int, scope: CoroutineScope) {
        job?.cancel()
        lastHost = host
        lastPort = port
        job = scope.launch(Dispatchers.IO) {
            var retries = 0
            while (isActive) {
                _state.value = State.CONNECTING
                Log.d(TAG, "Connecting to $host:$port (attempt ${retries + 1})")
                try {
                    val sock = Socket().also {
                        it.tcpNoDelay = true
                        it.keepAlive = true
                        it.connect(InetSocketAddress(host, port), 5_000)
                    }
                    socket = sock
                    retries = 0
                    Log.d(TAG, "Connected")
                    _state.value = State.CONNECTED

                    sock.use {
                        val out = it.getOutputStream()
                        for (packet in sendChannel) {
                            out.write(packet)
                        }
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Log.e(TAG, "Connection error: $ex")
                } finally {
                    socket = null
                }

                // Auto-reconnect with backoff (up to 5s)
                _state.value = State.DISCONNECTED
                Log.d(TAG, "Disconnected, will retry...")
                retries++
                val delayMs = (1000L * retries).coerceAtMost(5000L)
                delay(delayMs)
            }
        }
    }

    fun disconnect() {
        // Send release-all reports before closing
        sendReleaseAll()
        job?.cancel()
        job = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        _state.value = State.DISCONNECTED
    }

    /** Send empty mouse + keyboard reports to release all buttons/keys on the server. */
    private fun sendReleaseAll() {
        // Mouse: all buttons released, no movement
        sendChannel.trySend(byteArrayOf(0x01, 0, 0, 0, 0))
        // Keyboard: no modifiers, no keys
        sendChannel.trySend(ByteArray(9).also { it[0] = 0x02 })
    }

    // ── Report sending (safe to call from any thread) ─────────────────────────

    // Packet: 0x01, buttons, dx, dy, wheel
    fun sendMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        sendChannel.trySend(byteArrayOf(
            0x01,
            (buttons and 0x07).toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte(),
        ))
    }

    // Packet: 0x02, mods, reserved, key0..key5
    fun sendKeyboardReport(modifiers: Int, keycodes: IntArray) {
        val buf = ByteArray(9)
        buf[0] = 0x02
        buf[1] = modifiers.toByte()
        buf[2] = 0
        keycodes.take(6).forEachIndexed { i, key -> buf[i + 3] = key.toByte() }
        sendChannel.trySend(buf)
    }
}
