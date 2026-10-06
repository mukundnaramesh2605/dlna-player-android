package com.dlnaplayer.android.dlna

import android.util.Log
import android.util.Xml
import com.dlnaplayer.android.model.FileItem
import com.dlnaplayer.android.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Executes UPnP SOAP actions for AVTransport and RenderingControl services.
 */
class UPnPSoapClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    data class PositionInfo(
        val trackDuration: String,
        val relTime: String,
        val trackUri: String
    )

    data class TransportInfo(
        val currentTransportState: String,
        val currentTransportStatus: String,
        val currentSpeed: String
    )

    suspend fun setAVTransportURI(
        controlUrl: String,
        mediaUrl: String,
        fileItem: FileItem
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // Attempt 1: Full DLNA DIDL metadata with DLNA.ORG flags and size
        val fullMeta = buildFullDidlMetadata(mediaUrl, fileItem)
        val bodyFull = """
            <u:SetAVTransportURI xmlns:u="$AV_TRANSPORT_SERVICE_TYPE">
                <InstanceID>0</InstanceID>
                <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
                <CurrentURIMetaData>${escapeXml(fullMeta)}</CurrentURIMetaData>
            </u:SetAVTransportURI>
        """.trimIndent()

        val fullResult = executeSoap(controlUrl, AV_TRANSPORT_SERVICE_TYPE, "SetAVTransportURI", bodyFull)
        if (fullResult.isSuccess) {
            Log.d(TAG, "SetAVTransportURI succeeded with full DLNA metadata")
            return@withContext Result.success(Unit)
        }

        // Attempt 2: Minimal DIDL metadata (generic protocolInfo)
        Log.w(TAG, "Full DIDL metadata rejected (${fullResult.exceptionOrNull()?.message}). Retrying with simple DIDL...")
        val simpleMeta = buildSimpleDidlMetadata(mediaUrl, fileItem)
        val bodySimple = """
            <u:SetAVTransportURI xmlns:u="$AV_TRANSPORT_SERVICE_TYPE">
                <InstanceID>0</InstanceID>
                <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
                <CurrentURIMetaData>${escapeXml(simpleMeta)}</CurrentURIMetaData>
            </u:SetAVTransportURI>
        """.trimIndent()

        val simpleResult = executeSoap(controlUrl, AV_TRANSPORT_SERVICE_TYPE, "SetAVTransportURI", bodySimple)
        if (simpleResult.isSuccess) {
            Log.d(TAG, "SetAVTransportURI succeeded with simple DIDL metadata")
            return@withContext Result.success(Unit)
        }

        // Attempt 3: Empty metadata (for non-strict renderers)
        Log.w(TAG, "Simple DIDL metadata rejected (${simpleResult.exceptionOrNull()?.message}). Retrying with empty metadata...")
        val bodyEmpty = """
            <u:SetAVTransportURI xmlns:u="$AV_TRANSPORT_SERVICE_TYPE">
                <InstanceID>0</InstanceID>
                <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
                <CurrentURIMetaData></CurrentURIMetaData>
            </u:SetAVTransportURI>
        """.trimIndent()

        val emptyResult = executeSoap(controlUrl, AV_TRANSPORT_SERVICE_TYPE, "SetAVTransportURI", bodyEmpty)
        if (emptyResult.isSuccess) {
            Log.d(TAG, "SetAVTransportURI succeeded with empty metadata")
            return@withContext Result.success(Unit)
        }

        val finalError = fullResult.exceptionOrNull() ?: simpleResult.exceptionOrNull() ?: emptyResult.exceptionOrNull()
        Result.failure(finalError ?: Exception("SetAVTransportURI failed on $controlUrl"))
    }

    suspend fun play(controlUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        val body = """
            <u:Play xmlns:u="$AV_TRANSPORT_SERVICE_TYPE">
                <InstanceID>0</InstanceID>
                <Speed>1</Speed>
            </u:Play>
        """.trimIndent()

        executeSoap(controlUrl, AV_TRANSPORT_SERVICE_TYPE, "Play", body).map { }
    }

    suspend fun pause(controlUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        val body = """
            <u:Pause xmlns:u="$AV_TRANSPORT_SERVICE_TYPE">
                <InstanceID>0</InstanceID>
            </u:Pause>
        """.trimIndent()

        executeSoap(controlUrl, AV_TRANSPORT_SERVICE_TYPE, "Pause", body).map { }
    }

    suspend fun stop(controlUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        val body = """
            <u:Stop xmlns:u="$AV_TRANSPORT_SERVICE_TYPE">
                <InstanceID>0</InstanceID>
            </u:Stop>
        """.trimIndent()

        executeSoap(controlUrl, AV_TRANSPORT_SERVICE_TYPE, "Stop", body).map { }
    }

    suspend fun seek(controlUrl: String, timeTarget: String): Result<Unit> = withContext(Dispatchers.IO) {
        val body = """
            <u:Seek xmlns:u="$AV_TRANSPORT_SERVICE_TYPE">
                <InstanceID>0</InstanceID>
                <Unit>REL_TIME</Unit>
                <Target>$timeTarget</Target>
            </u:Seek>
        """.trimIndent()

        executeSoap(controlUrl, AV_TRANSPORT_SERVICE_TYPE, "Seek", body).map { }
    }

    suspend fun getPositionInfo(controlUrl: String): Result<PositionInfo> = withContext(Dispatchers.IO) {
        val body = """
            <u:GetPositionInfo xmlns:u="$AV_TRANSPORT_SERVICE_TYPE">
                <InstanceID>0</InstanceID>
            </u:GetPositionInfo>
        """.trimIndent()

        executeSoap(controlUrl, AV_TRANSPORT_SERVICE_TYPE, "GetPositionInfo", body).map { xml ->
            val duration = extractTagValue(xml, "TrackDuration") ?: "00:00:00"
            val relTime = extractTagValue(xml, "RelTime") ?: "00:00:00"
            val trackUri = extractTagValue(xml, "TrackURI") ?: ""
            PositionInfo(trackDuration = duration, relTime = relTime, trackUri = trackUri)
        }
    }

    suspend fun getTransportInfo(controlUrl: String): Result<TransportInfo> = withContext(Dispatchers.IO) {
        val body = """
            <u:GetTransportInfo xmlns:u="$AV_TRANSPORT_SERVICE_TYPE">
                <InstanceID>0</InstanceID>
            </u:GetTransportInfo>
        """.trimIndent()

        executeSoap(controlUrl, AV_TRANSPORT_SERVICE_TYPE, "GetTransportInfo", body).map { xml ->
            val state = extractTagValue(xml, "CurrentTransportState") ?: "STOPPED"
            val status = extractTagValue(xml, "CurrentTransportStatus") ?: "OK"
            val speed = extractTagValue(xml, "CurrentSpeed") ?: "1"
            TransportInfo(currentTransportState = state, currentTransportStatus = status, currentSpeed = speed)
        }
    }

    suspend fun setVolume(controlUrl: String, volume: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val clamped = volume.coerceIn(0, 100)
        val body = """
            <u:SetVolume xmlns:u="$RENDERING_CONTROL_SERVICE_TYPE">
                <InstanceID>0</InstanceID>
                <Channel>Master</Channel>
                <DesiredVolume>$clamped</DesiredVolume>
            </u:SetVolume>
        """.trimIndent()

        executeSoap(controlUrl, RENDERING_CONTROL_SERVICE_TYPE, "SetVolume", body).map { }
    }

    suspend fun getVolume(controlUrl: String): Result<Int> = withContext(Dispatchers.IO) {
        val body = """
            <u:GetVolume xmlns:u="$RENDERING_CONTROL_SERVICE_TYPE">
                <InstanceID>0</InstanceID>
                <Channel>Master</Channel>
            </u:GetVolume>
        """.trimIndent()

        executeSoap(controlUrl, RENDERING_CONTROL_SERVICE_TYPE, "GetVolume", body).map { xml ->
            extractTagValue(xml, "CurrentVolume")?.toIntOrNull() ?: 50
        }
    }

    suspend fun setMute(controlUrl: String, mute: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val muteVal = if (mute) "1" else "0"
        val body = """
            <u:SetMute xmlns:u="$RENDERING_CONTROL_SERVICE_TYPE">
                <InstanceID>0</InstanceID>
                <Channel>Master</Channel>
                <DesiredMute>$muteVal</DesiredMute>
            </u:SetMute>
        """.trimIndent()

        executeSoap(controlUrl, RENDERING_CONTROL_SERVICE_TYPE, "SetMute", body).map { }
    }

    suspend fun getMute(controlUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val body = """
            <u:GetMute xmlns:u="$RENDERING_CONTROL_SERVICE_TYPE">
                <InstanceID>0</InstanceID>
                <Channel>Master</Channel>
            </u:GetMute>
        """.trimIndent()

        executeSoap(controlUrl, RENDERING_CONTROL_SERVICE_TYPE, "GetMute", body).map { xml ->
            val muteVal = extractTagValue(xml, "CurrentMute")
            muteVal == "1" || muteVal.equals("true", ignoreCase = true)
        }
    }

    private fun executeSoap(
        controlUrl: String,
        serviceType: String,
        action: String,
        actionBody: String
    ): Result<String> {
        // Construct strictly formatted SOAP envelope with zero leading whitespace before XML declaration
        val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>$actionBody</s:Body></s:Envelope>"

        val soapActionHeader = "\"$serviceType#$action\""
        val requestBody = envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())

        val request = Request.Builder()
            .url(controlUrl)
            .addHeader("SOAPAction", soapActionHeader)
            .addHeader("User-Agent", "Android DLNA/1.50 UPnP/1.0")
            .post(requestBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                Result.success(bodyString)
            } else {
                val errorCode = extractTagValue(bodyString, "errorCode")
                val errorDesc = extractTagValue(bodyString, "errorDescription")
                val msg = if (errorCode != null) "UPnP Error $errorCode: $errorDesc" else "HTTP ${response.code}: $bodyString"
                Log.w(TAG, "SOAP action $action on $controlUrl failed: $msg")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "SOAP action $action exception on $controlUrl", e)
            Result.failure(e)
        }
    }

    private fun buildFullDidlMetadata(mediaUrl: String, fileItem: FileItem): String {
        val upnpClass = when (fileItem.mediaType) {
            MediaType.VIDEO -> "object.item.videoItem"
            MediaType.AUDIO -> "object.item.audioItem.musicTrack"
            MediaType.IMAGE -> "object.item.imageItem.photo"
            MediaType.OTHER -> "object.item"
        }
        val mime = fileItem.mimeType ?: FileItem.guessMimeType(fileItem.extension)
        val title = escapeXml(fileItem.name)
        val sizeAttr = if (fileItem.size > 0) " size=\"${fileItem.size}\"" else ""
        val protocolInfo = "http-get:*:$mime:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                "<dc:title>$title</dc:title>" +
                "<upnp:class>$upnpClass</upnp:class>" +
                "<res protocolInfo=\"$protocolInfo\"$sizeAttr>$mediaUrl</res>" +
                "</item></DIDL-Lite>"
    }

    private fun buildSimpleDidlMetadata(mediaUrl: String, fileItem: FileItem): String {
        val upnpClass = when (fileItem.mediaType) {
            MediaType.VIDEO -> "object.item.videoItem"
            MediaType.AUDIO -> "object.item.audioItem.musicTrack"
            MediaType.IMAGE -> "object.item.imageItem.photo"
            MediaType.OTHER -> "object.item"
        }
        val mime = fileItem.mimeType ?: FileItem.guessMimeType(fileItem.extension)
        val title = escapeXml(fileItem.name)

        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
                "<dc:title>$title</dc:title>" +
                "<upnp:class>$upnpClass</upnp:class>" +
                "<res protocolInfo=\"http-get:*:$mime:*\">$mediaUrl</res>" +
                "</item></DIDL-Lite>"
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun extractTagValue(xml: String, tagName: String): String? {
        return try {
            val parser = Xml.newPullParser().apply {
                setInput(StringReader(xml))
            }
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.equals(tagName, ignoreCase = true)) {
                    return parser.nextText().trim()
                }
                eventType = parser.next()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "UPnPSoapClient"
        const val AV_TRANSPORT_SERVICE_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RENDERING_CONTROL_SERVICE_TYPE = "urn:schemas-upnp-org:service:RenderingControl:1"
    }
}
