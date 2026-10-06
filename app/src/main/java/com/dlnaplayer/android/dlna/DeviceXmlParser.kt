package com.dlnaplayer.android.dlna

import com.dlnaplayer.android.model.DlnaDevice
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI

/**
 * Robust UPnP Device Description XML parser.
 * Extracts friendly name, manufacturer, UDN, and service control URLs (AVTransport, RenderingControl).
 * Utilizes XmlPullParser with an automatic regular expression fallback for malformed or non-standard XML.
 */
object DeviceXmlParser {

    fun parse(locationUrl: String, xml: String): DlnaDevice? {
        val baseUri = try { URI(locationUrl) } catch (e: Exception) { return null }
        val hostIp = baseUri.host ?: ""

        var rootFriendlyName = ""
        var embeddedFriendlyName = ""
        var manufacturer = ""
        var modelName = ""
        var udn = ""
        var iconPath: String? = null

        var avTransportControlUrl = ""
        var renderingControlUrl: String? = null

        try {
            val factory = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val parser = factory.newPullParser().apply {
                setInput(StringReader(xml))
            }

            var eventType = parser.eventType
            var currentTag = ""
            var inService = false
            var currentServiceType = ""
            var currentServiceId = ""
            var currentControlUrl = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = name
                        if (name.equals("service", ignoreCase = true)) {
                            inService = true
                            currentServiceType = ""
                            currentServiceId = ""
                            currentControlUrl = ""
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            if (inService) {
                                when {
                                    currentTag.equals("serviceType", ignoreCase = true) -> currentServiceType = text
                                    currentTag.equals("serviceId", ignoreCase = true) -> currentServiceId = text
                                    currentTag.equals("controlURL", ignoreCase = true) -> currentControlUrl = text
                                }
                            } else {
                                when {
                                    currentTag.equals("friendlyName", ignoreCase = true) -> {
                                        if (rootFriendlyName.isEmpty()) rootFriendlyName = text
                                        else embeddedFriendlyName = text
                                    }
                                    currentTag.equals("manufacturer", ignoreCase = true) && manufacturer.isEmpty() -> manufacturer = text
                                    currentTag.equals("modelName", ignoreCase = true) && modelName.isEmpty() -> modelName = text
                                    currentTag.equals("UDN", ignoreCase = true) && udn.isEmpty() -> udn = text
                                    currentTag.equals("url", ignoreCase = true) && iconPath == null -> iconPath = text
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        currentTag = ""
                        if (name.equals("service", ignoreCase = true)) {
                            inService = false
                            val isAvTransport = currentServiceType.contains("AVTransport", ignoreCase = true) ||
                                    currentServiceId.contains("AVTransport", ignoreCase = true)
                            val isRenderingControl = currentServiceType.contains("RenderingControl", ignoreCase = true) ||
                                    currentServiceId.contains("RenderingControl", ignoreCase = true)

                            if (isAvTransport && currentControlUrl.isNotBlank()) {
                                avTransportControlUrl = resolveUrl(baseUri, currentControlUrl)
                            } else if (isRenderingControl && currentControlUrl.isNotBlank()) {
                                renderingControlUrl = resolveUrl(baseUri, currentControlUrl)
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (ignored: Exception) {
            // PullParser error, fall back to regex extraction below
        }

        // Regex fallback if AVTransport controlURL was not found via PullParser
        if (avTransportControlUrl.isEmpty()) {
            val serviceRegex = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL)
            for (match in serviceRegex.findAll(xml)) {
                val block = match.groupValues[1]
                if (block.contains("AVTransport", ignoreCase = true)) {
                    val ctrlMatch = Regex("<controlURL>(.*?)</controlURL>", RegexOption.IGNORE_CASE).find(block)
                    if (ctrlMatch != null) {
                        avTransportControlUrl = resolveUrl(baseUri, ctrlMatch.groupValues[1].trim())
                    }
                } else if (block.contains("RenderingControl", ignoreCase = true) && renderingControlUrl == null) {
                    val ctrlMatch = Regex("<controlURL>(.*?)</controlURL>", RegexOption.IGNORE_CASE).find(block)
                    if (ctrlMatch != null) {
                        renderingControlUrl = resolveUrl(baseUri, ctrlMatch.groupValues[1].trim())
                    }
                }
            }
        }

        // Regex fallback for friendly name, model and UDN
        if (rootFriendlyName.isEmpty()) {
            val fnMatch = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.IGNORE_CASE).find(xml)
            if (fnMatch != null) rootFriendlyName = fnMatch.groupValues[1].trim()
        }
        if (modelName.isEmpty()) {
            val mnMatch = Regex("<modelName>(.*?)</modelName>", RegexOption.IGNORE_CASE).find(xml)
            if (mnMatch != null) modelName = mnMatch.groupValues[1].trim()
        }
        if (udn.isEmpty()) {
            val udnMatch = Regex("<UDN>(.*?)</UDN>", RegexOption.IGNORE_CASE).find(xml)
            udn = udnMatch?.groupValues?.get(1)?.trim() ?: locationUrl
        }

        if (avTransportControlUrl.isEmpty()) {
            return null
        }

        val friendlyName = rootFriendlyName.ifEmpty {
            embeddedFriendlyName.ifEmpty {
                modelName.ifEmpty { "UPnP MediaRenderer ($hostIp)" }
            }
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

    fun resolveUrl(baseUri: URI, relativeOrAbsolute: String): String {
        return try {
            val trimmed = relativeOrAbsolute.trim()
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                trimmed
            } else if (trimmed.startsWith("/")) {
                val portStr = if (baseUri.port != -1) ":${baseUri.port}" else ""
                "${baseUri.scheme}://${baseUri.host}$portStr$trimmed"
            } else {
                baseUri.resolve(trimmed).toString()
            }
        } catch (e: Exception) {
            relativeOrAbsolute
        }
    }
}
