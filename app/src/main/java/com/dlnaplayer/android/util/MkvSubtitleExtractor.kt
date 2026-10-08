package com.dlnaplayer.android.util

import android.util.Log
import com.dlnaplayer.android.model.SubtitleSource
import com.dlnaplayer.android.model.SubtitleTrack
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Lightweight, high-performance streaming EBML/Matroska parser for inspecting
 * and extracting embedded subtitle tracks (SRT, ASS, SSA, WebVTT) from MKV files.
 */
object MkvSubtitleExtractor {

    private const val TAG = "MkvSubtitleExtractor"

    // Standard Matroska EBML Element IDs
    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_TRACK_NAME = 0x536EL
    private const val ID_TRACK_LANG = 0x22B59CL
    private const val ID_TRACK_LANG_IETF = 0x22B59DL
    private const val ID_FLAG_DEFAULT = 0x88L
    private const val ID_FLAG_FORCED = 0x55AAL

    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMECODE = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL

    private const val TRACK_TYPE_SUBTITLE = 17L

    /**
     * Quickly scans an MKV file and returns all embedded subtitle tracks.
     * Skips clusters entirely, taking only a few milliseconds.
     */
    fun getEmbeddedSubtitleTracks(file: File): List<SubtitleTrack> {
        if (!file.exists() || !file.canRead() || file.length() < 16) {
            return emptyList()
        }

        val tracks = mutableListOf<SubtitleTrack>()
        try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()

                // Read EBML header
                val ebmlId = readElementId(raf)
                if (ebmlId != ID_EBML) return emptyList()
                val ebmlSize = readVint(raf)
                if (ebmlSize < 0) return emptyList()
                raf.seek(raf.filePointer + ebmlSize)

                // Locate Segment
                while (raf.filePointer < length) {
                    val segId = readElementId(raf)
                    if (segId < 0) break
                    val segSize = readVint(raf)
                    if (segSize < 0) break
                    if (segId == ID_SEGMENT) {
                        break
                    }
                    raf.seek(raf.filePointer + segSize)
                }

                // Scan inside Segment for Tracks element
                while (raf.filePointer < length) {
                    val elemId = readElementId(raf)
                    if (elemId < 0) break
                    val elemSize = readVint(raf)
                    if (elemSize < 0) break

                    val contentStart = raf.filePointer
                    if (elemId == ID_TRACKS) {
                        parseTracks(raf, contentStart + elemSize, file, tracks)
                        break // Done!
                    } else {
                        raf.seek(contentStart + elemSize)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error inspecting MKV subtitle tracks: ${e.message}")
        }

        return tracks
    }

    private fun parseTracks(
        raf: RandomAccessFile,
        endOffset: Long,
        file: File,
        outTracks: MutableList<SubtitleTrack>
    ) {
        while (raf.filePointer < endOffset) {
            val trackEntryId = readElementId(raf)
            if (trackEntryId < 0) break
            val entrySize = readVint(raf)
            if (entrySize < 0) break
            val entryEnd = raf.filePointer + entrySize

            if (trackEntryId == ID_TRACK_ENTRY) {
                var trackNum: Long? = null
                var trackType: Long? = null
                var codecId: String? = null
                var trackName: String? = null
                var trackLang: String? = null
                var isDefault = false
                var isForced = false

                while (raf.filePointer < entryEnd) {
                    val subId = readElementId(raf)
                    if (subId < 0) break
                    val subSize = readVint(raf)
                    if (subSize < 0) break
                    val subEnd = raf.filePointer + subSize

                    when (subId) {
                        ID_TRACK_NUMBER -> trackNum = readUInt(raf, subSize.toInt())
                        ID_TRACK_TYPE -> trackType = readUInt(raf, subSize.toInt())
                        ID_CODEC_ID -> codecId = readString(raf, subSize.toInt())
                        ID_TRACK_NAME -> trackName = readString(raf, subSize.toInt())
                        ID_TRACK_LANG -> if (trackLang == null) trackLang = readString(raf, subSize.toInt())
                        ID_TRACK_LANG_IETF -> trackLang = readString(raf, subSize.toInt())
                        ID_FLAG_DEFAULT -> isDefault = readUInt(raf, subSize.toInt()) == 1L
                        ID_FLAG_FORCED -> isForced = readUInt(raf, subSize.toInt()) == 1L
                        else -> raf.seek(subEnd)
                    }
                }

                if (trackType == TRACK_TYPE_SUBTITLE && trackNum != null) {
                    val cleanCodec = codecId ?: "S_TEXT/UTF8"
                    val title = trackName ?: (trackLang?.uppercase(Locale.ROOT)?.let { "$it Subtitle" } ?: "Track $trackNum")
                    outTracks.add(
                        SubtitleTrack(
                            id = "mkv_${file.name.hashCode()}_track_$trackNum",
                            title = title,
                            language = trackLang,
                            codec = cleanCodec,
                            source = SubtitleSource.EmbeddedMkv(
                                mkvFile = file,
                                trackNumber = trackNum,
                                codec = cleanCodec
                            ),
                            isDefault = isDefault,
                            isForced = isForced
                        )
                    )
                }
            } else {
                raf.seek(entryEnd)
            }
        }
    }

    private data class SubtitleCue(
        val startMs: Long,
        var durationMs: Long? = null,
        val text: String
    )

    /**
     * Extracts an embedded subtitle track and writes it to a standard .srt subtitle file.
     */
    fun extractTrackToSrt(
        mkvFile: File,
        targetTrackNumber: Long,
        codec: String?,
        outputFile: File
    ): Result<File> {
        if (!mkvFile.exists()) {
            return Result.failure(IllegalArgumentException("MKV file does not exist"))
        }

        outputFile.parentFile?.mkdirs()
        val cues = mutableListOf<SubtitleCue>()

        try {
            RandomAccessFile(mkvFile, "r").use { raf ->
                val length = raf.length()

                // Skip EBML Header
                val ebmlId = readElementId(raf)
                if (ebmlId != ID_EBML) return Result.failure(Exception("Not EBML"))
                val ebmlSize = readVint(raf)
                if (ebmlSize < 0) return Result.failure(Exception("Invalid size"))
                raf.seek(raf.filePointer + ebmlSize)

                // Find Segment
                while (raf.filePointer < length) {
                    val segId = readElementId(raf)
                    if (segId < 0) break
                    val segSize = readVint(raf)
                    if (segSize < 0) break
                    if (segId == ID_SEGMENT) {
                        break
                    }
                    raf.seek(raf.filePointer + segSize)
                }

                var clusterTimecodeMs = 0L

                // Stream through top-level elements of Segment
                while (raf.filePointer < length) {
                    val elemId = readElementId(raf)
                    if (elemId < 0) break
                    val elemSize = readVint(raf)
                    if (elemSize < 0) break
                    val elemEnd = raf.filePointer + elemSize

                    if (elemId == ID_CLUSTER) {
                        while (raf.filePointer < elemEnd) {
                            val cSubId = readElementId(raf)
                            if (cSubId < 0) break
                            val cSubSize = readVint(raf)
                            if (cSubSize < 0) break
                            val cSubEnd = raf.filePointer + cSubSize

                            when (cSubId) {
                                ID_CLUSTER_TIMECODE -> {
                                    clusterTimecodeMs = readUInt(raf, cSubSize.toInt())
                                }

                                ID_SIMPLE_BLOCK, ID_BLOCK -> {
                                    val (trackNum, trackNumLen) = readVintWithLength(raf)
                                    if (trackNum == targetTrackNumber) {
                                        val relTime = raf.readShort().toLong()
                                        raf.readByte() // flags
                                        val payloadLen = (cSubSize - trackNumLen - 3).toInt()
                                        if (payloadLen > 0) {
                                            val bytes = ByteArray(payloadLen)
                                            raf.readFully(bytes)
                                            val text = String(bytes, StandardCharsets.UTF_8).trim()
                                            val absTime = (clusterTimecodeMs + relTime).coerceAtLeast(0L)
                                            cues.add(SubtitleCue(startMs = absTime, text = text))
                                        }
                                    } else {
                                        raf.seek(cSubEnd)
                                    }
                                }

                                ID_BLOCK_GROUP -> {
                                    var currentCueIndex = -1
                                    while (raf.filePointer < cSubEnd) {
                                        val bgSubId = readElementId(raf)
                                        if (bgSubId < 0) break
                                        val bgSubSize = readVint(raf)
                                        if (bgSubSize < 0) break
                                        val bgSubEnd = raf.filePointer + bgSubSize

                                        when (bgSubId) {
                                            ID_BLOCK -> {
                                                val (trackNum, trackNumLen) = readVintWithLength(raf)
                                                if (trackNum == targetTrackNumber) {
                                                    val relTime = raf.readShort().toLong()
                                                    raf.readByte() // flags
                                                    val payloadLen = (bgSubSize - trackNumLen - 3).toInt()
                                                    if (payloadLen > 0) {
                                                        val bytes = ByteArray(payloadLen)
                                                        raf.readFully(bytes)
                                                        val text = String(bytes, StandardCharsets.UTF_8).trim()
                                                        val absTime = (clusterTimecodeMs + relTime).coerceAtLeast(0L)
                                                        cues.add(SubtitleCue(startMs = absTime, text = text))
                                                        currentCueIndex = cues.size - 1
                                                    }
                                                } else {
                                                    raf.seek(bgSubEnd)
                                                }
                                            }

                                            ID_BLOCK_DURATION -> {
                                                val duration = readUInt(raf, bgSubSize.toInt())
                                                if (currentCueIndex in cues.indices) {
                                                    cues[currentCueIndex].durationMs = duration
                                                }
                                            }

                                            else -> raf.seek(bgSubEnd)
                                        }
                                    }
                                }

                                else -> raf.seek(cSubEnd)
                            }
                        }
                    } else {
                        raf.seek(elemEnd)
                    }
                }
            }

            // Write out standard SRT file
            BufferedWriter(FileWriter(outputFile)).use { writer ->
                var srtIndex = 1
                val isAss = codec?.contains("ASS", ignoreCase = true) == true ||
                        codec?.contains("SSA", ignoreCase = true) == true

                for (i in cues.indices) {
                    val cue = cues[i]
                    var text = cue.text
                    if (text.isBlank()) continue

                    if (isAss) {
                        text = cleanAssText(text)
                    }

                    if (text.isBlank()) continue

                    val startMs = cue.startMs
                    val nextStart = if (i + 1 < cues.size) cues[i + 1].startMs else startMs + 3500L
                    val duration = cue.durationMs ?: (nextStart - startMs).coerceIn(1000L, 4500L)
                    val endMs = startMs + duration

                    writer.write(srtIndex.toString())
                    writer.newLine()
                    writer.write("${formatSrtTimestamp(startMs)} --> ${formatSrtTimestamp(endMs)}")
                    writer.newLine()
                    writer.write(text)
                    writer.newLine()
                    writer.newLine()
                    srtIndex++
                }
            }

            Log.i(TAG, "Extracted ${cues.size} subtitle cues to ${outputFile.name}")
            return Result.success(outputFile)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Failed extracting MKV subtitle track", e)
            return Result.failure(e)
        }
    }

    private fun cleanAssText(raw: String): String {
        var text = raw
        val parts = text.split(",", limit = 9)
        if (parts.size == 9) {
            text = parts[8]
        }
        text = text.replace(Regex("\\{[^}]*\\}"), "")
        text = text.replace("\\N", "\n").replace("\\n", "\n").trim()
        return text
    }

    private fun formatSrtTimestamp(ms: Long): String {
        val totalSec = ms / 1000
        val remMs = ms % 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, remMs)
    }

    private fun readElementId(raf: RandomAccessFile): Long {
        val b0 = raf.read()
        if (b0 == -1) return -1L
        var id = b0.toLong()
        when {
            (b0 and 0x80) != 0 -> { /* 1 byte */ }
            (b0 and 0x40) != 0 -> {
                id = (id shl 8) or raf.readUnsignedByte().toLong()
            }
            (b0 and 0x20) != 0 -> {
                id = (id shl 16) or (raf.readUnsignedByte().toLong() shl 8) or raf.readUnsignedByte().toLong()
            }
            (b0 and 0x10) != 0 -> {
                id = (id shl 24) or (raf.readUnsignedByte().toLong() shl 16) or (raf.readUnsignedByte().toLong() shl 8) or raf.readUnsignedByte().toLong()
            }
            else -> return -1L
        }
        return id
    }

    private fun readVint(raf: RandomAccessFile): Long {
        val b0 = raf.read()
        if (b0 == -1) return -1L
        var mask = 0x80
        var len = 1
        while (mask != 0 && (b0 and mask) == 0) {
            mask = mask shr 1
            len++
        }
        if (len > 8) return -1L

        var value = (b0 and mask.inv()).toLong()
        for (i in 1 until len) {
            val next = raf.read()
            if (next == -1) return -1L
            value = (value shl 8) or next.toLong()
        }
        return value
    }

    private fun readVintWithLength(raf: RandomAccessFile): Pair<Long, Int> {
        val b0 = raf.read()
        if (b0 == -1) return Pair(-1L, 0)
        var mask = 0x80
        var len = 1
        while (mask != 0 && (b0 and mask) == 0) {
            mask = mask shr 1
            len++
        }
        if (len > 8) return Pair(-1L, 0)

        var value = (b0 and mask.inv()).toLong()
        for (i in 1 until len) {
            val next = raf.read()
            if (next == -1) return Pair(-1L, 0)
            value = (value shl 8) or next.toLong()
        }
        return Pair(value, len)
    }

    private fun readUInt(raf: RandomAccessFile, size: Int): Long {
        var value = 0L
        for (i in 0 until size) {
            value = (value shl 8) or raf.readUnsignedByte().toLong()
        }
        return value
    }

    private fun readString(raf: RandomAccessFile, size: Int): String {
        val bytes = ByteArray(size)
        raf.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8).trimEnd { it == '\u0000' }
    }
}
