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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Ultra-resilient DLNA / UPnP discovery engine.
 * Employs a multi-layered discovery strategy:
 *  1. Multi-interface Multicast SSDP with burst transmissions (ssdp:all, MediaRenderer, AVTransport, Samsung).
 *  2. Multicast group listener (Port 1900) for SSDP NOTIFY announcements.
 *  3. Unicast SSDP & Subnet Port Prober (bypasses router multicast/IGMP filtering).
 *  4. Direct IP probing support for manual device entry.
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
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    fun startDiscovery() {
        if (_isScanning.value) return
        scanJob?.cancel()

        scanJob = scope.launch(Dispatchers.IO) {
            try {
                _isScanning.value = true
                multicastLock = NetworkUtils.createMulticastLock(context, "SsdpDiscoveryLock")
                multicastLock?.acquire()

                val activeInterfaces = NetworkUtils.getAllActiveMulticastInterfaces()
                Log.d(TAG, "Active network interfaces for SSDP: ${activeInterfaces.map { it.name }}")

                // Launch concurrent discovery layers
                val jobs = mutableListOf<Job>()

                // Layer 1: SSDP Multicast on all active network interfaces
                for (iface in activeInterfaces) {
                    jobs.add(launch(Dispatchers.IO) {
                        runInterfaceSsdpSearch(iface)
                    })
                }

                // Layer 2: Multicast group listener on port 1900 for NOTIFY packets
                jobs.add(launch(Dispatchers.IO) {
                    runMulticastGroupListener(activeInterfaces.firstOrNull())
                })

                // Layer 3: Subnet probe to bypass router multicast filtering
                jobs.add(launch(Dispatchers.IO) {
                    // Small delay to allow fast multicast first
                    delay(500)
                    probeLocalSubnet()
                })

                // Wait for scan window
                delay(SCAN_DURATION_MS)
                jobs.forEach { it.cancel() }
            } catch (e: Exception) {
                Log.e(TAG, "Error in SSDP discovery", e)
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

    /**
     * Probes an IP address directly (for manual entry or subnet sweeps).
     */
    suspend fun probeDeviceAtIp(input: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return@withContext false

        // Normalize input: extract host and optional path/port
        val targetHost = when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> {
                try { URI(trimmed).host ?: trimmed } catch (e: Exception) { trimmed }
            }
            trimmed.contains(":") -> trimmed.substringBefore(":")
            trimmed.contains("/") -> trimmed.substringBefore("/")
            else -> trimmed
        }

        // Build list of candidate URLs
        val candidateUrls = mutableListOf<String>()

        // If user provided a specific full URL, try it first
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            candidateUrls.add(trimmed)
        } else if (trimmed.contains(":")) {
            candidateUrls.add("http://$trimmed")
            candidateUrls.add("http://$trimmed/dmr")
            candidateUrls.add("http://$trimmed/description.xml")
        }

        // Standard DLNA endpoints with Samsung Smart Monitor DMR (9197) at top priority
        val defaultEndpoints = listOf(
            "http://$targetHost:9197/dmr",                      // Samsung Smart Monitor / Tizen TV DMR (PRIMARY!)
            "http://$targetHost:8001/api/v2/",                  // Samsung SmartView REST API
            "http://$targetHost:7676/smp_2_",                   // Samsung TV DLNA descriptor
            "http://$targetHost:7678/npi_device_description.xml",
            "http://$targetHost:8080/description.xml",
            "http://$targetHost:8080/device-desc.xml",
            "http://$targetHost:49152/description.xml",
            "http://$targetHost:49153/description.xml",
            "http://$targetHost:1900/description.xml"
        )
        for (ep in defaultEndpoints) {
            if (!candidateUrls.contains(ep)) {
                candidateUrls.add(ep)
            }
        }

        val fastClient = httpClient.newBuilder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build()

        for (url in candidateUrls) {
            try {
                val req = Request.Builder().url(url).build()
                val resp = fastClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (url.contains("/api/v2/")) {
                        val parsed = parseSamsungRestApi(targetHost, body, fastClient)
                        if (parsed != null) {
                            addDevice(parsed)
                            return@withContext true
                        }
                    } else if (body.contains("AVTransport", ignoreCase = true) || body.contains("RenderingControl", ignoreCase = true)) {
                        val parsed = parseDeviceXml(url, body)
                        if (parsed != null) {
                            addDevice(parsed)
                            return@withContext true
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        // Try unicast M-SEARCH to <host>:1900
        try {
            val unicastSocket = MulticastSocket(0).apply { soTimeout = 1200 }
            val ssdpMsg = buildSearchMessage("ssdp:all").toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(ssdpMsg, ssdpMsg.size, InetAddress.getByName(targetHost), SSDP_PORT)
            unicastSocket.send(packet)

            val buffer = ByteArray(4096)
            val receivePacket = DatagramPacket(buffer, buffer.size)
            unicastSocket.receive(receivePacket)
            val response = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
            val locationUrl = extractHeader(response, "LOCATION")
            if (!locationUrl.isNullOrBlank()) {
                fetchAndParseDeviceDescription(locationUrl)
                return@withContext true
            }
        } catch (ignored: Exception) {}

        return@withContext false
    }

    private suspend fun runInterfaceSsdpSearch(iface: NetworkInterface) = withContext(Dispatchers.IO) {
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(0).apply {
                reuseAddress = true
                soTimeout = 3000
                networkInterface = iface
                timeToLive = 4
            }

            val searchTargets = listOf(
                "ssdp:all",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
                "upnp:rootdevice",
                "urn:samsung.com:device:RemoteControlReceiver:1",
                "urn:dial-multiscreen-org:service:dial:1"
            )

            val ssdpAddress = InetAddress.getByName(SSDP_ADDRESS)

            // Send multiple bursts to overcome Wi-Fi packet loss
            launch {
                for (burst in 1..3) {
                    if (!isActive) break
                    for (target in searchTargets) {
                        try {
                            val message = buildSearchMessage(target)
                            val data = message.toByteArray(Charsets.UTF_8)
                            val packet = DatagramPacket(data, data.size, ssdpAddress, SSDP_PORT)
                            socket.send(packet)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error sending M-SEARCH on ${iface.name}", e)
                        }
                    }
                    delay(350)
                }
            }

            val buffer = ByteArray(8192)
            while (isActive) {
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
                    // Normal socket timeout
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "SSDP receive error on ${iface.name}: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start SSDP search on ${iface.name}: ${e.message}")
        } finally {
            try { socket?.close() } catch (ignored: Exception) {}
        }
    }

    private suspend fun runMulticastGroupListener(iface: NetworkInterface?) = withContext(Dispatchers.IO) {
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(SSDP_PORT).apply {
                reuseAddress = true
                soTimeout = 4000
                if (iface != null) {
                    networkInterface = iface
                }
            }
            val group = InetAddress.getByName(SSDP_ADDRESS)
            socket.joinGroup(group)

            val buffer = ByteArray(8192)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val content = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val locationUrl = extractHeader(content, "LOCATION")
                    if (!locationUrl.isNullOrBlank()) {
                        launch(Dispatchers.IO) {
                            fetchAndParseDeviceDescription(locationUrl)
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "Multicast 1900 listener error: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind port 1900 multicast listener: ${e.message}")
        } finally {
            try { socket?.close() } catch (ignored: Exception) {}
        }
    }

    /**
     * Scans local /24 subnet for responsive TV ports to bypass router multicast filtering.
     */
    private suspend fun probeLocalSubnet() = withContext(Dispatchers.IO) {
        val subnet = NetworkUtils.getSubnetPrefix() ?: return@withContext
        val localIp = NetworkUtils.getLocalIpAddress() ?: ""

        val semaphore = Semaphore(32) // 32 concurrent probes
        val probePorts = listOf(9197, 8001, 7676, 8080) // Prioritize Samsung DMR (9197) and REST (8001)

        val deferreds = (1..254).map { i ->
            val targetIp = "$subnet$i"
            if (targetIp == localIp) return@map null

            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (!isActive) return@withPermit
                    // Fast port check
                    for (port in probePorts) {
                        if (isPortOpen(targetIp, port, 150)) {
                            Log.d(TAG, "Discovered active device port at $targetIp:$port")
                            probeDeviceAtIp(targetIp)
                            break
                        }
                    }
                }
            }
        }

        deferreds.filterNotNull().awaitAll()
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun fetchAndParseDeviceDescription(locationUrl: String) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(locationUrl).build()
                val response = httpClient.newCall(request).execute()
                val xmlBody = response.body?.string() ?: return@withContext

                val parsedDevice = parseDeviceXml(locationUrl, xmlBody)
                if (parsedDevice != null && parsedDevice.avTransportControlUrl.isNotBlank()) {
                    addDevice(parsedDevice)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch/parse device at $locationUrl: ${e.message}")
            }
        }
    }

    private fun addDevice(device: DlnaDevice) {
        deviceMap[device.udn] = device
        _devices.value = deviceMap.values.toList().sortedBy { it.friendlyName }
        Log.i(TAG, "Registered DLNA Renderer: ${device.friendlyName} (${device.ipAddress})")
    }

    private fun parseSamsungRestApi(ip: String, json: String, client: OkHttpClient): DlnaDevice? {
        return try {
            val root = JSONObject(json)
            val name = root.optString("name", "Samsung Smart TV")
            val id = root.optString("id", "uuid:samsung-$ip")
            val devObj = root.optJSONObject("device")
            val model = devObj?.optString("modelName", "Smart TV") ?: "Smart TV"

            // 1. Try to fetch the real DMR descriptor from port 9197 (Samsung MediaRenderer)
            try {
                val dmrReq = Request.Builder().url("http://$ip:9197/dmr").build()
                val dmrResp = client.newCall(dmrReq).execute()
                if (dmrResp.isSuccessful) {
                    val dmrXml = dmrResp.body?.string().orEmpty()
                    val parsedDmr = parseDeviceXml("http://$ip:9197/dmr", dmrXml)
                    if (parsedDmr != null && parsedDmr.avTransportControlUrl.isNotBlank()) {
                        Log.i(TAG, "Successfully resolved Samsung DMR via port 9197: ${parsedDmr.avTransportControlUrl}")
                        return parsedDmr
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Direct port 9197 XML fetch on Samsung failed (${e.message}), applying standard Tizen endpoints")
            }

            // 2. Default to standard Tizen DMR port 9197 endpoints
            DlnaDevice(
                udn = id,
                friendlyName = name,
                manufacturer = "Samsung Electronics",
                modelName = model,
                locationUrl = "http://$ip:8001/api/v2/",
                ipAddress = ip,
                avTransportControlUrl = "http://$ip:9197/upnp/control/AVTransport1",
                renderingControlUrl = "http://$ip:9197/upnp/control/RenderingControl1"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Samsung REST JSON", e)
            null
        }
    }

    fun parseDeviceXml(locationUrl: String, xml: String): DlnaDevice? {
        return DeviceXmlParser.parse(locationUrl, xml)
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

    companion object {
        private const val TAG = "SsdpDiscovery"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SCAN_DURATION_MS = 12000L
    }
}
