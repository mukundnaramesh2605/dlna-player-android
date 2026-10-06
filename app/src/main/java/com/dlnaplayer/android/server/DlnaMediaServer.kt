package com.dlnaplayer.android.server

import android.util.Log
import com.dlnaplayer.android.model.FileItem
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded HTTP server powered by NanoHTTPD to stream local media files to DLNA renderers.
 * Supports:
 *  - HTTP byte-range requests (RFC 7233) for seeking in audio and video files.
 *  - DLNA streaming headers (transferMode.dlna.org, contentFeatures.dlna.org).
 *  - Dynamic URL mapping for served files.
 */
class DlnaMediaServer(
    private val hostIp: String,
    val serverPort: Int = DEFAULT_PORT
) : NanoHTTPD(null, serverPort) {

    private val mediaRegistry = ConcurrentHashMap<String, File>()

    /**
     * Registers a file and returns the full HTTP streaming URL for the DLNA renderer.
     */
    fun registerMedia(file: File): String {
        val id = file.absolutePath.hashCode().toString().replace("-", "x")
        mediaRegistry[id] = file
        val encodedName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        return "http://$hostIp:$serverPort/media/$id/$encodedName"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Request: ${session.method} $uri Headers: ${session.headers}")

        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method Not Allowed")
        }

        // Match /media/{id}/{filename} or query param
        val targetFile = resolveRequestedFile(uri, session.parameters)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File Not Found")

        if (!targetFile.exists() || !targetFile.isFile || !targetFile.canRead()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Cannot access file")
        }

        return serveFileWithRanges(session, targetFile)
    }

    private fun resolveRequestedFile(uri: String, params: Map<String, List<String>>): File? {
        // Try query param ?path=...
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

        // Try /media/{id}/...
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
     * Serves file handling HTTP byte-range requests for DLNA seeking.
     */
    private fun serveFileWithRanges(session: IHTTPSession, file: File): Response {
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
            addDlnaHeaders(response)
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

                val fis = FileInputStream(file)
                if (rangeStart > 0) {
                    fis.skip(rangeStart)
                }

                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    mimeType,
                    fis,
                    contentLength
                )
                response.addHeader("Content-Range", "bytes $rangeStart-$rangeEnd/$fileLength")
                response.addHeader("Content-Length", contentLength.toString())
                addDlnaHeaders(response)
                return response
            } else {
                // Full content request (200 OK)
                val fis = FileInputStream(file)
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    fis,
                    fileLength
                )
                response.addHeader("Content-Length", fileLength.toString())
                addDlnaHeaders(response)
                return response
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException serving file: ${file.name}", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Streaming error: ${e.message}")
        }
    }

    private fun addDlnaHeaders(response: Response) {
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("transferMode.dlna.org", "Streaming")
        response.addHeader(
            "contentFeatures.dlna.org",
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        )
        response.addHeader("Connection", "keep-alive")
        response.addHeader("Server", "DLNAPlayer/1.0 UPnP/1.0 DLNADOC/1.50")
    }

    companion object {
        private const val TAG = "DlnaMediaServer"
        const val DEFAULT_PORT = 8192
    }
}
