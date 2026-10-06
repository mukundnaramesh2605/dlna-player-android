package com.dlnaplayer.android.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.util.Xml
import com.dlnaplayer.android.model.DlnaDevice
import com.dlnaplayer.android.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Discovers DLNA/UPnP MediaRenderer devices on the local network via SSDP (Simple Service Discovery Protocol).
 * Broadcasts M-SEARCH multicast packets to 239.255.255.250:1900 and parses device description XML documents.
 */
class SsdpDiscovery(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val deviceMap = ConcurrentHashMap<String, DlnaDevice>()
    private var scanJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun startDiscovery() {
        if (_isScanning.value) return
        scanJob?.cancel()

        scanJob = scope.launch(Dispatchers.IO) {
            try {
                _isScanning.value = true
                multicastLock = NetworkUtils.createMulticastLock(context, "SsdpDiscoveryLock")
                multicastLock?.acquire()

                val searchTargets = listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "upnp:rootdevice"
                )

                // Run SSDP socket listener
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 4000
                    bind(InetSocketAddress(0))
                }

                try {
                    // Send search requests for all target schemas
                    val ssdpAddress = InetAddress.getByName(SSDP_ADDRESS)
                    for (target in searchTargets) {
                        val message = buildSearchMessage(target)
                        val data = message.toByteArray(Charsets.UTF_8)
                        val packet = DatagramPacket(data, data.size, ssdpAddress, SSDP_PORT)
                        socket.send(packet)
                    }

                    val buffer = ByteArray(4096)
                    val startTime = System.currentTimeMillis()

                    while (isActive && (System.currentTimeMillis() - startTime) < SCAN_DURATION_MS) {
                        try {
                            val receivePacket = DatagramPacket(buffer, buffer.size)
                            socket.receive(receivePacket)

                            val response = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                            val locationUrl = extractHeader(response, "LOCATION")

                            if (!locationUrl.isNullOrBlank()) {
                                launch(Dispatchers.IO) {
                                    fetchAndParseDeviceDescription(locationUrl)
                                }
                            }
                        } catch (e: java.net.SocketTimeoutException) {
                            // Normal timeout on receive
                        } catch (e: Exception) {
                            if (isActive) {
                                Log.w(TAG, "Socket receive error", e)
                            }
                            break
                        }
                    }
                } finally {
                    socket.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during SSDP discovery", e)
            } finally {
                try {
                    if (multicastLock?.isHeld == true) {
                        multicastLock?.release()
                    }
                } catch (ignored: Exception) {}
                _isScanning.value = false
            }
        }
    }

    fun stopDiscovery() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (ignored: Exception) {}
    }

    fun clearDevices() {
        deviceMap.clear()
        _devices.value = emptyList()
    }

    private fun buildSearchMessage(st: String): String {
        return buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 3\r\n")
            append("ST: $st\r\n")
            append("\r\n")
        }
    }

    private fun extractHeader(response: String, headerName: String): String? {
        val lines = response.split("\r\n")
        for (line in lines) {
            val colonIndex = line.indexOf(':')
            if (colonIndex != -1) {
                val key = line.substring(0, colonIndex).trim()
                if (key.equals(headerName, ignoreCase = true)) {
                    return line.substring(colonIndex + 1).trim()
                }
            }
        }
        return null
    }

    private suspend fun fetchAndParseDeviceDescription(locationUrl: String) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(locationUrl).build()
                val response = httpClient.newCall(request).execute()
                val xmlBody = response.body?.string() ?: return@withContext

                val parsedDevice = parseDeviceXml(locationUrl, xmlBody)
                if (parsedDevice != null && parsedDevice.avTransportControlUrl.isNotBlank()) {
                    deviceMap[parsedDevice.udn] = parsedDevice
                    _devices.value = deviceMap.values.toList().sortedBy { it.friendlyName }
                    Log.i(TAG, "Discovered DLNA Renderer: ${parsedDevice.friendlyName} (${parsedDevice.ipAddress})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch/parse device at $locationUrl: ${e.message}")
            }
        }
    }

    private fun parseDeviceXml(locationUrl: String, xml: String): DlnaDevice? {
        val baseUri = URI(locationUrl)
        val hostIp = baseUri.host ?: ""

        val parser = Xml.newPullParser().apply {
            setInput(StringReader(xml))
        }

        var eventType = parser.eventType
        var currentTag = ""

        var friendlyName = ""
        var manufacturer = ""
        var modelName = ""
        var udn = ""
        var iconPath: String? = null

        var avTransportControlUrl = ""
        var renderingControlUrl: String? = null

        var inService = false
        var currentServiceType = ""
        var currentControlUrl = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name ?: ""
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = name
                    if (name.equals("service", ignoreCase = true)) {
                        inService = true
                        currentServiceType = ""
                        currentControlUrl = ""
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        if (inService) {
                            when {
                                currentTag.equals("serviceType", ignoreCase = true) -> currentServiceType = text
                                currentTag.equals("controlURL", ignoreCase = true) -> currentControlUrl = text
                            }
                        } else {
                            when {
                                currentTag.equals("friendlyName", ignoreCase = true) && friendlyName.isEmpty() -> friendlyName = text
                                currentTag.equals("manufacturer", ignoreCase = true) && manufacturer.isEmpty() -> manufacturer = text
                                currentTag.equals("modelName", ignoreCase = true) && modelName.isEmpty() -> modelName = text
                                currentTag.equals("UDN", ignoreCase = true) && udn.isEmpty() -> udn = text
                                currentTag.equals("url", ignoreCase = true) && iconPath == null -> iconPath = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name.equals("service", ignoreCase = true)) {
                        inService = false
                        if (currentServiceType.contains("AVTransport", ignoreCase = true)) {
                            avTransportControlUrl = resolveUrl(baseUri, currentControlUrl)
                        } else if (currentServiceType.contains("RenderingControl", ignoreCase = true)) {
                            renderingControlUrl = resolveUrl(baseUri, currentControlUrl)
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        if (udn.isEmpty()) {
            udn = locationUrl
        }
        if (friendlyName.isEmpty()) {
            friendlyName = modelName.ifEmpty { "UPnP MediaRenderer ($hostIp)" }
        }

        if (avTransportControlUrl.isEmpty()) {
            // Not an AVTransport-capable MediaRenderer device
            return null
        }

        val fullIconUrl = iconPath?.let { resolveUrl(baseUri, it) }

        return DlnaDevice(
            udn = udn,
            friendlyName = friendlyName,
            manufacturer = manufacturer,
            modelName = modelName,
            locationUrl = locationUrl,
            ipAddress = hostIp,
            avTransportControlUrl = avTransportControlUrl,
            renderingControlUrl = renderingControlUrl,
            iconUrl = fullIconUrl
        )
    }

    private fun resolveUrl(baseUri: URI, relativeOrAbsolute: String): String {
        return try {
            val trimmed = relativeOrAbsolute.trim()
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                trimmed
            } else {
                baseUri.resolve(trimmed).toString()
            }
        } catch (e: Exception) {
            relativeOrAbsolute
        }
    }

    companion object {
        private const val TAG = "SsdpDiscovery"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SCAN_DURATION_MS = 6000L
    }
}
