package dev.phantom.androidmouse

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ServerDiscovery"
private const val SERVICE_TYPE = "_androidmouse._tcp."

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
)

class ServerDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private var listening = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery start failed: $errorCode")
            listening = false
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery stop failed: $errorCode")
        }
        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "Discovery started")
            listening = true
        }
        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "Discovery stopped")
            listening = false
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Found service: ${serviceInfo.serviceName}")
            nsdManager.resolveService(serviceInfo, resolveListener())
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Lost service: ${serviceInfo.serviceName}")
            _servers.value = _servers.value.filter { it.name != serviceInfo.serviceName }
        }
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
        }
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val server = DiscoveredServer(
                name = serviceInfo.serviceName,
                host = host,
                port = serviceInfo.port,
            )
            Log.d(TAG, "Resolved: $server")
            _servers.value = _servers.value.filter { it.name != server.name } + server
        }
    }

    fun startDiscovery() {
        if (listening) return
        _servers.value = emptyList()
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to start discovery: $ex")
        }
    }

    fun stopDiscovery() {
        if (!listening) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to stop discovery: $ex")
        }
    }
}
