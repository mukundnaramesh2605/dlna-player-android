package com.dlnaplayer.android.server

import android.util.Log
import com.dlnaplayer.android.model.FileItem
import com.dlnaplayer.android.util.BytePatch
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded HTTP server powered by NanoHTTPD to stream local media files and subtitles to DLNA renderers.
 * Supports:
 *  - HTTP byte-range requests (RFC 7233) for seeking in audio and video files.
 *  - DLNA streaming headers (transferMode.dlna.org, contentFeatures.dlna.org).
 *  - Serving external and extracted subtitle files (.srt, .vtt) with Samsung SEC caption headers.
 *  - Dynamic URL mapping for served files.
 *  - Per-audio-track URL variants: the same file served with a header [BytePatch] applied,
 *    so the renderer sees the chosen audio track as the default one.
 */
class DlnaMediaServer(
    private val hostIp: String,
    val serverPort: Int = DEFAULT_PORT
) : NanoHTTPD(null, serverPort) {

    private val mediaRegistry = ConcurrentHashMap<String, File>()
    private val subtitleRegistry = ConcurrentHashMap<String, File>()
    private val patchRegistry = ConcurrentHashMap<String, BytePatch>()

    fun getMediaId(file: File): String {
        return file.absolutePath.hashCode().toString().replace("-", "x")
    }

    /**
     * Registers a file and returns the full HTTP streaming URL for the DLNA renderer.
     */
    fun registerMedia(file: File): String = registerMediaVariant(file, null, null)

    /**
     * Registers a variant of [file] served with [patch] applied (e.g. a different default
     * audio track). Each variant gets its own URL so renderers don't reuse cached headers.
     * Subtitles stay keyed by the base media id and are shared by all variants.
     */
    fun registerMediaVariant(file: File, variant: Int?, patch: BytePatch?): String {
        val baseId = getMediaId(file)
        val id = if (variant != null && patch != null) "${baseId}a$variant" else baseId
        mediaRegistry[id] = file
        if (patch != null && id != baseId) patchRegistry[id] = patch else patchRegistry.remove(id)
        val encodedName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        return "http://$hostIp:$serverPort/media/$id/$encodedName"
    }

    /**
     * Registers an active subtitle file for a media item.
     * Returns the subtitle HTTP streaming URL, or null if cleared.
     */
    fun registerSubtitle(mediaId: String, subtitleFile: File?): String? {
        if (subtitleFile == null || !subtitleFile.exists() || !subtitleFile.canRead()) {
            subtitleRegistry.remove(mediaId)
            return null
        }
        subtitleRegistry[mediaId] = subtitleFile
        val encodedName = URLEncoder.encode(subtitleFile.name, "UTF-8").replace("+", "%20")
        return "http://$hostIp:$serverPort/subtitles/$mediaId/$encodedName"
    }

    /**
     * Returns the current subtitle URL for a media item if one is registered.
     */
    fun getSubtitleUrl(mediaId: String): String? {
        val subFile = subtitleRegistry[mediaId] ?: return null
        if (!subFile.exists() || !subFile.canRead()) return null
        val encodedName = URLEncoder.encode(subFile.name, "UTF-8").replace("+", "%20")
        return "http://$hostIp:$serverPort/subtitles/$mediaId/$encodedName"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Request: ${session.method} $uri Headers: ${session.headers}")

        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method Not Allowed")
        }

        val parts = uri.split("/").filter { it.isNotBlank() }

        // 1. Handle dedicated subtitle request: /subtitles/{id}/{filename}
        if (parts.size >= 2 && parts[0] == "subtitles") {
            val id = parts[1]
            val subFile = subtitleRegistry[id]
            if (subFile != null && subFile.exists() && subFile.canRead()) {
                return serveSubtitleFile(session, subFile)
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Subtitle Not Found")
        }

        // 2. Handle sidecar subtitle request on media path: /media/{id}/*.srt or *.vtt
        if (parts.size >= 2 && parts[0] == "media") {
            val id = baseMediaId(parts[1])
            val requestedName = parts.getOrNull(2)?.lowercase(Locale.ROOT)
            if (requestedName != null && (requestedName.endsWith(".srt") || requestedName.endsWith(".vtt") || requestedName.endsWith(".sub"))) {
                val subFile = subtitleRegistry[id]
                if (subFile != null && subFile.exists() && subFile.canRead()) {
                    return serveSubtitleFile(session, subFile)
                }
            }
        }

        // 3. Match /media/{id}/{filename} or query param
        val targetFile = resolveRequestedFile(uri, session.parameters)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File Not Found")

        if (!targetFile.exists() || !targetFile.isFile || !targetFile.canRead()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Cannot access file")
        }

        val mediaId = getMediaId(targetFile)
        val activeSubtitleUrl = getSubtitleUrl(mediaId)
        val patch = parts.getOrNull(1)?.takeIf { parts[0] == "media" }?.let { patchRegistry[it] }

        return serveFileWithRanges(session, targetFile, activeSubtitleUrl, patch)
    }

    private fun resolveRequestedFile(uri: String, params: Map<String, List<String>>): File? {
        val pathParam = params["path"]?.firstOrNull()
        if (!pathParam.isNullOrBlank()) {
            try {
                val decoded = URLDecoder.decode(pathParam, "UTF-8")
                val f = File(decoded)
                if (f.exists()) return f
            } catch (e: Exception) {
                Log.w(TAG, "Error decoding path param", e)
            }
        }

        val parts = uri.split("/").filter { it.isNotBlank() }
        if (parts.size >= 2 && parts[0] == "media") {
            val id = parts[1]
            val registered = mediaRegistry[id]
            if (registered != null && registered.exists()) {
                return registered
            }
        }

        return null
    }

    /**
     * Serves a subtitle file (.srt, .vtt) with proper MIME types and CORS headers.
     */
    private fun serveSubtitleFile(session: IHTTPSession, subFile: File): Response {
        val length = subFile.length()
        val ext = subFile.extension.lowercase(Locale.ROOT)
        val mimeType = when (ext) {
            "vtt" -> "text/vtt; charset=utf-8"
            "srt" -> "text/srt; charset=utf-8"
            else -> "text/plain; charset=utf-8"
        }

        if (session.method == Method.HEAD) {
            val response = newFixedLengthResponse(Response.Status.OK, mimeType, null, length)
            response.addHeader("Content-Length", length.toString())
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Accept-Ranges", "bytes")
            return response
        }

        return try {
            val fis = FileInputStream(subFile)
            val response = newFixedLengthResponse(Response.Status.OK, mimeType, fis, length)
            response.addHeader("Content-Length", length.toString())
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Accept-Ranges", "bytes")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Error serving subtitle: ${subFile.name}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error reading subtitle")
        }
    }

    /**
     * Serves file handling HTTP byte-range requests for DLNA seeking.
     */
    private fun serveFileWithRanges(
        session: IHTTPSession,
        file: File,
        subtitleUrl: String? = null,
        patch: BytePatch? = null
    ): Response {
        val fileLength = file.length()
        val mimeType = FileItem.guessMimeType(file.extension)
        val rangeHeader = session.headers["range"]

        if (session.method == Method.HEAD) {
            val response = newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                null,
                fileLength
            )
            response.addHeader("Content-Length", fileLength.toString())
            addDlnaHeaders(response, subtitleUrl)
            return response
        }

        try {
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                var rangeStart: Long = 0
                var rangeEnd: Long = fileLength - 1

                val rangeSpec = rangeHeader.substring(6).trim()
                val minusIndex = rangeSpec.indexOf('-')

                if (minusIndex != -1) {
                    val startStr = rangeSpec.substring(0, minusIndex).trim()
                    val endStr = rangeSpec.substring(minusIndex + 1).trim()

                    if (startStr.isNotEmpty()) {
                        rangeStart = startStr.toLongOrNull() ?: 0L
                    }
                    if (endStr.isNotEmpty()) {
                        rangeEnd = endStr.toLongOrNull() ?: (fileLength - 1)
                    }
                }

                if (rangeStart > rangeEnd || rangeStart >= fileLength) {
                    val errorResponse = newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE,
                        MIME_PLAINTEXT,
                        "Requested Range Not Satisfiable"
                    )
                    errorResponse.addHeader("Content-Range", "bytes */$fileLength")
                    return errorResponse
                }

                rangeEnd = rangeEnd.coerceAtMost(fileLength - 1)
                val contentLength = rangeEnd - rangeStart + 1

                val fis = PatchedFileInputStream(file, rangeStart, contentLength, patch)

                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    mimeType,
                    fis,
                    contentLength
                )
                response.addHeader("Content-Range", "bytes $rangeStart-$rangeEnd/$fileLength")
                response.addHeader("Content-Length", contentLength.toString())
                addDlnaHeaders(response, subtitleUrl)
                return response
            } else {
                // Full content request (200 OK)
                val fis = PatchedFileInputStream(file, 0L, fileLength, patch)
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    fis,
                    fileLength
                )
                response.addHeader("Content-Length", fileLength.toString())
                addDlnaHeaders(response, subtitleUrl)
                return response
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException serving file: ${file.name}", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Streaming error: ${e.message}")
        }
    }

    private fun addDlnaHeaders(response: Response, subtitleUrl: String? = null) {
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("transferMode.dlna.org", "Streaming")
        response.addHeader(
            "contentFeatures.dlna.org",
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        )
        response.addHeader("Connection", "keep-alive")
        response.addHeader("Server", "DLNAPlayer/1.0 UPnP/1.0 DLNADOC/1.50")

        // Samsung SEC DLNA subtitle header
        if (!subtitleUrl.isNullOrBlank()) {
            response.addHeader("CaptionInfo.sec", subtitleUrl)
        }
    }

    /** Strips an audio-variant suffix ("…a2") so variants share the base media's subtitles. */
    private fun baseMediaId(id: String): String = id.replace(VARIANT_SUFFIX, "")

    companion object {
        private const val TAG = "DlnaMediaServer"
        private val VARIANT_SUFFIX = Regex("a\\d+$")
        const val DEFAULT_PORT = 8192
    }
}
