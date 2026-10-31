package com.dlnaplayer.android.util

import android.util.Log
import com.dlnaplayer.android.model.AudioTrack
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.CRC32

/**
 * A same-length byte replacement applied on the fly while a file is streamed.
 * Because the length never changes, every byte offset in the file stays valid,
 * so HTTP Range requests (seeking) keep working exactly as for the original file.
 */
class BytePatch(val offset: Long, val bytes: ByteArray) {
    val end: Long get() = offset + bytes.size
}

/**
 * Lists the audio tracks of MKV/WebM and MP4/MOV files and builds a [BytePatch] that
 * makes a chosen track the one a DLNA renderer will play.
 *
 * UPnP AVTransport has no standard "select audio stream" action — renderers simply play
 * the first/default audio track. So instead of asking the renderer, we rewrite the file's
 * header while serving it:
 *
 *  - Matroska: the TrackEntry elements inside `Tracks` are reordered so the chosen audio
 *    entry comes first among the audio entries, and the audio entries are re-serialized so
 *    only the chosen one is default (FlagDefault omitted = 1, others get FlagDefault 0).
 *    Any size difference is absorbed by an EBML Void element, and the CRC-32 of `Tracks`
 *    is recomputed. If there isn't enough room, it falls back to reordering and flipping
 *    only the FlagDefault elements that already exist.
 *  - MP4/MOV: the `trak` boxes inside `moov` are reordered the same way, and the
 *    `track_enabled` bit in each audio `tkhd` is set only for the chosen track.
 *
 * Only element order and flag values change. The rewritten region has exactly the same size,
 * and no element moves outside it, so cue/sample tables (absolute file offsets) stay valid.
 */
object AudioTrackSelector {

    private const val TAG = "AudioTrackSelector"

    private const val MAX_MKV_TRACKS_BYTES = 16L * 1024 * 1024
    private const val MAX_MP4_MOOV_BYTES = 64L * 1024 * 1024

    enum class Container { MATROSKA, MP4, UNSUPPORTED }

    data class AudioTrackScan(
        val tracks: List<AudioTrack>,
        /** True when the container supports switching via [buildPatch]. */
        val canSwitch: Boolean
    ) {
        companion object {
            val EMPTY = AudioTrackScan(emptyList(), canSwitch = false)
        }
    }

    fun containerOf(file: File): Container = when (file.extension.lowercase(Locale.ROOT)) {
        "mkv", "mka", "mk3d", "webm" -> Container.MATROSKA
        "mp4", "m4v", "m4a", "mov", "3gp", "3g2" -> Container.MP4
        else -> Container.UNSUPPORTED
    }

    /** Reads the file header and returns its audio tracks in header order. */
    fun scan(file: File): AudioTrackScan {
        if (!file.isFile || !file.canRead()) return AudioTrackScan.EMPTY
        return try {
            val tracks = when (containerOf(file)) {
                Container.MATROSKA -> readMatroska(file)?.let { matroskaAudioTracks(it) }
                Container.MP4 -> readMp4(file)?.let { mp4AudioTracks(it) }
                Container.UNSUPPORTED -> null
            } ?: return AudioTrackScan.EMPTY
            AudioTrackScan(tracks, canSwitch = tracks.size > 1)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read audio tracks of ${file.name}: ${e.message}")
            AudioTrackScan.EMPTY
        }
    }

    /**
     * Builds the header patch that makes [track] the renderer's audio track.
     * Returns null if the file can't be parsed or the track no longer exists.
     */
    fun buildPatch(file: File, track: AudioTrack): BytePatch? {
        return try {
            when (containerOf(file)) {
                Container.MATROSKA -> readMatroska(file)?.let { patchMatroska(it, track.index) }
                Container.MP4 -> readMp4(file)?.let { patchMp4(it, track.index) }
                Container.UNSUPPORTED -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not build audio patch for ${file.name}: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------

    /** A child element/box inside a parsed buffer: [start, end) including its header. */
    private class Child(val id: Long, val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    /** A same-width unsigned integer field inside an entry, relative to the entry start. */
    private class Field(val relOffset: Int, val width: Int)

    /**
     * Rebuilds [buffer] with the audio entries (at [audioSlots] in [children]) permuted so
     * that audio entry [chosen] comes first. [fixup] is called for every audio entry after it
     * is copied, with the entry's new absolute offset in the output buffer.
     */
    private fun permuteAudioEntries(
        buffer: ByteArray,
        children: List<Child>,
        audioSlots: List<Int>,
        chosen: Int,
        fixup: (out: ByteArray, newStart: Int, audioOrdinal: Int) -> Unit
    ): ByteArray {
        val out = buffer.copyOf()
        val newOrder = listOf(chosen) + audioSlots.indices.filter { it != chosen }
        var cursor = children.first().start
        var audioSlotCursor = 0
        for ((i, child) in children.withIndex()) {
            if (i in audioSlots) {
                val ordinal = newOrder[audioSlotCursor++]
                val source = children[audioSlots[ordinal]]
                System.arraycopy(buffer, source.start, out, cursor, source.length)
                fixup(out, cursor, ordinal)
                cursor += source.length
            } else {
                System.arraycopy(buffer, child.start, out, cursor, child.length)
                cursor += child.length
            }
        }
        check(cursor == children.last().end) { "Permutation changed region size" }
        return out
    }

    private fun writeUInt(out: ByteArray, offset: Int, width: Int, value: Long) {
        var v = value
        for (i in width - 1 downTo 0) {
            out[offset + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    private fun readUInt(buf: ByteArray, offset: Int, width: Int): Long {
        var v = 0L
        for (i in 0 until width) v = (v shl 8) or (buf[offset + i].toLong() and 0xFF)
        return v
    }

    // ---------------------------------------------------------------------------------------
    // Matroska / WebM (EBML)
    // ---------------------------------------------------------------------------------------

    private const val MKV_EBML = 0x1A45DFA3L
    private const val MKV_SEGMENT = 0x18538067L
    private const val MKV_SEEK_HEAD = 0x114D9B74L
    private const val MKV_SEEK = 0x4DBBL
    private const val MKV_SEEK_ID = 0x53ABL
    private const val MKV_SEEK_POSITION = 0x53ACL
    private const val MKV_TRACKS = 0x1654AE6BL
    private const val MKV_CLUSTER = 0x1F43B675L
    private const val MKV_CRC32 = 0xBFL
    private const val MKV_VOID = 0xECL
    private const val MKV_TRACK_ENTRY = 0xAEL
    private const val MKV_TRACK_NUMBER = 0xD7L
    private const val MKV_TRACK_TYPE = 0x83L
    private const val MKV_FLAG_ENABLED = 0xB9L
    private const val MKV_FLAG_DEFAULT = 0x88L
    private const val MKV_CODEC_ID = 0x86L
    private const val MKV_NAME = 0x536EL
    private const val MKV_LANGUAGE = 0x22B59CL
    private const val MKV_LANGUAGE_BCP47 = 0x22B59DL
    private const val MKV_AUDIO = 0xE1L
    private const val MKV_SAMPLING_FREQ = 0xB5L
    private const val MKV_CHANNELS = 0x9FL
    private const val MKV_TYPE_AUDIO = 2L

    /** The raw `Tracks` payload of a Matroska file and where it lives in the file. */
    private class MatroskaTracks(val fileOffset: Long, val data: ByteArray)

    private class MkvEntry(
        val child: Child,
        val number: Long,
        val type: Long,
        val codec: String?,
        val name: String?,
        val language: String?,
        val channels: Int?,
        val sampleRate: Int?,
        val flagDefault: Field?,
        val flagDefaultValue: Long,
        val flagEnabled: Field?
    )

    /** EBML variable-length integer as read from a file or buffer. */
    private class Vint(val value: Long, val length: Int, val isUnknown: Boolean)

    private fun readMkvVint(read: () -> Int): Vint? {
        val b0 = read()
        if (b0 < 0) return null
        var len = 1
        var mask = 0x80
        while (len <= 8 && (b0 and mask) == 0) {
            mask = mask shr 1
            len++
        }
        if (len > 8) return null
        var value = (b0 and (mask - 1)).toLong()
        var allOnes = value == (mask - 1).toLong()
        repeat(len - 1) {
            val b = read()
            if (b < 0) return null
            if (b != 0xFF) allOnes = false
            value = (value shl 8) or b.toLong()
        }
        return Vint(value, len, allOnes)
    }

    /** Element IDs keep their length-marker bits. */
    private fun readMkvId(read: () -> Int): Pair<Long, Int>? {
        val b0 = read()
        if (b0 < 0) return null
        val len = when {
            b0 and 0x80 != 0 -> 1
            b0 and 0x40 != 0 -> 2
            b0 and 0x20 != 0 -> 3
            b0 and 0x10 != 0 -> 4
            else -> return null
        }
        var id = b0.toLong()
        repeat(len - 1) {
            val b = read()
            if (b < 0) return null
            id = (id shl 8) or b.toLong()
        }
        return id to len
    }

    private fun readMatroska(file: File): MatroskaTracks? {
        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()
            val read = { raf.read() }

            val (ebmlId, _) = readMkvId(read) ?: return null
            if (ebmlId != MKV_EBML) return null
            val ebmlSize = readMkvVint(read) ?: return null
            raf.seek(raf.filePointer + ebmlSize.value)

            // Find the Segment
            var segmentDataStart = -1L
            var segmentEnd = fileLength
            while (raf.filePointer < fileLength) {
                val (id, _) = readMkvId(read) ?: return null
                val size = readMkvVint(read) ?: return null
                if (id == MKV_SEGMENT) {
                    segmentDataStart = raf.filePointer
                    if (!size.isUnknown) segmentEnd = minOf(fileLength, segmentDataStart + size.value)
                    break
                }
                if (size.isUnknown) return null
                raf.seek(raf.filePointer + size.value)
            }
            if (segmentDataStart < 0) return null

            // Walk the Segment's top-level children until Tracks is found.
            // If we hit a Cluster first, fall back to the SeekHead's pointer to Tracks.
            var seekTracksPos: Long? = null
            var pos = segmentDataStart
            var jumpedViaSeekHead = false
            while (pos < segmentEnd) {
                raf.seek(pos)
                val (id, _) = readMkvId(read) ?: return null
                val size = readMkvVint(read) ?: return null
                val dataStart = raf.filePointer

                when (id) {
                    MKV_TRACKS -> {
                        if (size.isUnknown || size.value > MAX_MKV_TRACKS_BYTES) return null
                        val data = ByteArray(size.value.toInt())
                        raf.readFully(data)
                        return MatroskaTracks(dataStart, data)
                    }
                    MKV_SEEK_HEAD -> if (!size.isUnknown && size.value < 1_000_000) {
                        val data = ByteArray(size.value.toInt())
                        raf.readFully(data)
                        parseMkvSeekHead(data)?.let { seekTracksPos = segmentDataStart + it }
                    }
                }

                val reachedMedia = id == MKV_CLUSTER || size.isUnknown
                if (reachedMedia) {
                    val target = seekTracksPos
                    if (target == null || jumpedViaSeekHead) return null
                    jumpedViaSeekHead = true
                    pos = target
                } else {
                    pos = dataStart + size.value
                }
            }
            return null
        }
    }

    /** Returns the Tracks position (relative to the Segment data start) from a SeekHead. */
    private fun parseMkvSeekHead(data: ByteArray): Long? {
        for (seek in mkvChildren(data, 0, data.size)) {
            if (seek.id != MKV_SEEK) continue
            var seekId: Long? = null
            var seekPos: Long? = null
            forEachMkvField(data, seek) { field, off, len ->
                when (field.id) {
                    MKV_SEEK_ID -> seekId = readUInt(data, off, len)
                    MKV_SEEK_POSITION -> seekPos = readUInt(data, off, len)
                }
            }
            if (seekId == MKV_TRACKS) return seekPos
        }
        return null
    }

    /** Parses consecutive EBML elements in buf[from, to). Stops at the first malformed one. */
    private fun mkvChildren(buf: ByteArray, from: Int, to: Int): List<Child> {
        val result = mutableListOf<Child>()
        var p = from
        while (p < to) {
            var cursor = p
            val read = { if (cursor < to) buf[cursor++].toInt() and 0xFF else -1 }
            val (id, _) = readMkvId(read) ?: break
            val size = readMkvVint(read) ?: break
            if (size.isUnknown) break
            val end = cursor.toLong() + size.value
            if (end > to) break
            result.add(Child(id, p, end.toInt()))
            p = end.toInt()
        }
        return result
    }

    /** Calls [block] with (child, dataOffset, dataLength) for each direct child of [parent]. */
    private inline fun forEachMkvField(buf: ByteArray, parent: Child, block: (Child, Int, Int) -> Unit) {
        val dataStart = mkvDataStart(buf, parent)
        for (child in mkvChildren(buf, dataStart, parent.end)) {
            val off = mkvDataStart(buf, child)
            block(child, off, child.end - off)
        }
    }

    private fun mkvDataStart(buf: ByteArray, element: Child): Int {
        var cursor = element.start
        val read = { buf[cursor++].toInt() and 0xFF }
        readMkvId(read)
        readMkvVint(read)
        return cursor
    }

    private fun parseMkvEntries(tracks: MatroskaTracks): Pair<List<Child>, List<MkvEntry>> {
        val data = tracks.data
        val children = mkvChildren(data, 0, data.size)
        val entries = children.filter { it.id == MKV_TRACK_ENTRY }.map { entry ->
            var number = 0L
            var type = 0L
            var codec: String? = null
            var name: String? = null
            var language: String? = null
            var bcp47: String? = null
            var channels: Int? = null
            var sampleRate: Int? = null
            var flagDefault: Field? = null
            var flagDefaultValue = 1L // Matroska default when the element is absent
            var flagEnabled: Field? = null

            forEachMkvField(data, entry) { field, off, len ->
                when (field.id) {
                    MKV_TRACK_NUMBER -> number = readUInt(data, off, len)
                    MKV_TRACK_TYPE -> type = readUInt(data, off, len)
                    MKV_CODEC_ID -> codec = mkvString(data, off, len)
                    MKV_NAME -> name = mkvString(data, off, len)
                    MKV_LANGUAGE -> language = mkvString(data, off, len)
                    MKV_LANGUAGE_BCP47 -> bcp47 = mkvString(data, off, len)
                    MKV_FLAG_DEFAULT -> if (len in 1..8) {
                        flagDefault = Field(off - entry.start, len)
                        flagDefaultValue = readUInt(data, off, len)
                    }
                    MKV_FLAG_ENABLED -> if (len in 1..8) flagEnabled = Field(off - entry.start, len)
                    MKV_AUDIO -> forEachMkvField(data, field) { audioField, aoff, alen ->
                        when (audioField.id) {
                            MKV_CHANNELS -> channels = readUInt(data, aoff, alen).toInt()
                            MKV_SAMPLING_FREQ -> sampleRate = when (alen) {
                                4 -> ByteBuffer.wrap(data, aoff, 4).float.toInt()
                                8 -> ByteBuffer.wrap(data, aoff, 8).double.toInt()
                                else -> null
                            }
                        }
                    }
                }
            }
            MkvEntry(
                entry, number, type, codec, name, bcp47 ?: language ?: "eng", channels ?: 1, sampleRate,
                flagDefault, flagDefaultValue, flagEnabled
            )
        }
        return children to entries
    }

    private fun mkvString(buf: ByteArray, off: Int, len: Int): String =
        String(buf, off, len, StandardCharsets.UTF_8).trimEnd('\u0000').trim()

    private fun matroskaAudioTracks(tracks: MatroskaTracks): List<AudioTrack> {
        val audio = parseMkvEntries(tracks).second.filter { it.type == MKV_TYPE_AUDIO }
        val defaultOrdinal = audio.indexOfFirst { it.flagDefaultValue != 0L }.coerceAtLeast(0)
        return audio.mapIndexed { i, e ->
            AudioTrack(
                index = i,
                trackId = e.number,
                codec = e.codec,
                language = e.language,
                title = e.name,
                channels = e.channels,
                sampleRate = e.sampleRate,
                isDefault = i == defaultOrdinal
            )
        }
    }

    private fun patchMatroska(tracks: MatroskaTracks, chosen: Int): BytePatch? {
        val (children, entries) = parseMkvEntries(tracks)
        if (children.isEmpty()) return null
        val audioEntries = entries.filter { it.type == MKV_TYPE_AUDIO }
        if (chosen !in audioEntries.indices) return null
        val audioSlots = audioEntries.map { e -> children.indexOf(e.child) }

        val out = rebuildMatroskaTracks(tracks.data, children, audioEntries, audioSlots, chosen)
            ?: permuteInPlaceMatroska(tracks.data, children, audioEntries, audioSlots, chosen)

        // Keep an optional leading CRC-32 element valid (it covers everything after itself).
        val first = children.first()
        if (first.id == MKV_CRC32) {
            val crcData = mkvDataStart(out, first)
            if (first.end - crcData == 4) {
                val crc = CRC32().apply { update(out, first.end, out.size - first.end) }.value
                for (i in 0 until 4) out[crcData + i] = ((crc ushr (8 * i)) and 0xFF).toByte() // little-endian
            }
        }
        return BytePatch(tracks.fileOffset, out)
    }

    /**
     * Preferred strategy: re-serialize the audio TrackEntries so only the chosen one is default,
     * even when the other entries have no FlagDefault element (muxers omit it when it's 1).
     * Returns null when the result can't be made to fit the original `Tracks` size.
     */
    private fun rebuildMatroskaTracks(
        data: ByteArray,
        children: List<Child>,
        audioEntries: List<MkvEntry>,
        audioSlots: List<Int>,
        chosen: Int
    ): ByteArray? {
        if (children.last().end != data.size) return null
        val newOrder = listOf(chosen) + audioEntries.indices.filter { it != chosen }

        fun assemble(extraSizeWidth: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream(data.size)
            var audioCursor = 0
            for ((i, child) in children.withIndex()) {
                when {
                    i in audioSlots -> {
                        val ordinal = newOrder[audioCursor++]
                        val isChosen = ordinal == chosen
                        val entry = rebuildMkvAudioEntry(data, audioEntries[ordinal], isChosen, if (isChosen) extraSizeWidth else 0)
                            ?: return null
                        out.write(entry)
                    }
                    child.id == MKV_VOID -> Unit // reclaimed; re-added as padding below
                    else -> out.write(data, child.start, child.length)
                }
            }
            return out.toByteArray()
        }

        var body = assemble(0) ?: return null
        // A Void element needs at least 2 bytes; absorb a 1-byte gap by widening a size field.
        if (data.size - body.size == 1) body = assemble(1) ?: return null
        val slack = data.size - body.size
        return when {
            slack == 0 -> body
            slack >= 2 -> body + mkvVoid(slack)
            else -> null
        }
    }

    private fun rebuildMkvAudioEntry(data: ByteArray, entry: MkvEntry, isChosen: Boolean, extraSizeWidth: Int): ByteArray? {
        val element = entry.child
        val dataStart = mkvDataStart(data, element)
        val originalSizeWidth = dataStart - element.start - 1 // TrackEntry ID (0xAE) is 1 byte
        val body = java.io.ByteArrayOutputStream(element.length)
        for (field in mkvChildren(data, dataStart, element.end)) {
            when (field.id) {
                MKV_FLAG_DEFAULT, MKV_VOID -> Unit
                MKV_FLAG_ENABLED -> {
                    val copy = data.copyOfRange(field.start, field.end)
                    if (isChosen) {
                        val off = mkvDataStart(data, field) - field.start
                        writeUInt(copy, off, copy.size - off, 1)
                    }
                    body.write(copy)
                }
                else -> body.write(data, field.start, field.length)
            }
        }
        // Absent FlagDefault means 1, so only the non-chosen tracks need an explicit 0.
        if (!isChosen) body.write(byteArrayOf(0x88.toByte(), 0x81.toByte(), 0x00))
        val payload = body.toByteArray()
        val width = maxOf(mkvMinimalSizeWidth(payload.size.toLong()), originalSizeWidth) + extraSizeWidth
        if (width > 8) return null
        return byteArrayOf(0xAE.toByte()) + mkvEncodeSize(payload.size.toLong(), width) + payload
    }

    private fun mkvMinimalSizeWidth(value: Long): Int =
        (1..8).first { value < (1L shl (7 * it)) - 1 } // all-ones is reserved for "unknown"

    private fun mkvEncodeSize(value: Long, width: Int): ByteArray {
        val out = ByteArray(width)
        var v = value
        for (i in width - 1 downTo 0) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        out[0] = (out[0].toInt() or (0x80 ushr (width - 1))).toByte()
        return out
    }

    /** An EBML Void element occupying exactly [total] bytes (total >= 2). */
    private fun mkvVoid(total: Int): ByteArray {
        val width = (1..8).first { w -> total - 1 - w >= 0 && mkvMinimalSizeWidth((total - 1 - w).toLong()) <= w }
        val payload = total - 1 - width
        return byteArrayOf(0xEC.toByte()) + mkvEncodeSize(payload.toLong(), width) + ByteArray(payload)
    }

    /** Fallback: reorder entries and flip only FlagDefault elements that already exist. */
    private fun permuteInPlaceMatroska(
        data: ByteArray,
        children: List<Child>,
        audioEntries: List<MkvEntry>,
        audioSlots: List<Int>,
        chosen: Int
    ): ByteArray =
        permuteAudioEntries(data, children, audioSlots, chosen) { buf, newStart, ordinal ->
            val entry = audioEntries[ordinal]
            val isChosen = ordinal == chosen
            entry.flagDefault?.let { writeUInt(buf, newStart + it.relOffset, it.width, if (isChosen) 1 else 0) }
            if (isChosen) entry.flagEnabled?.let { writeUInt(buf, newStart + it.relOffset, it.width, 1) }
        }

    // ---------------------------------------------------------------------------------------
    // MP4 / QuickTime (ISO BMFF)
    // ---------------------------------------------------------------------------------------

    /** The raw `moov` payload and where it lives in the file. */
    private class Mp4Moov(val fileOffset: Long, val data: ByteArray)

    private class Mp4Trak(
        val child: Child,
        val trackId: Long,
        val handler: String?,
        val handlerName: String?,
        val title: String?,
        val language: String?,
        val codec: String?,
        val channels: Int?,
        val sampleRate: Int?,
        val tkhdFlagsRel: Int?,
        val enabled: Boolean
    )

    private fun fourCC(id: Long): String = String(
        byteArrayOf((id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte()),
        StandardCharsets.ISO_8859_1
    )

    private fun boxType(s: String): Long = s.toByteArray(StandardCharsets.ISO_8859_1)
        .fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

    private val MP4_MOOV = boxType("moov")
    private val MP4_TRAK = boxType("trak")
    private val MP4_TKHD = boxType("tkhd")
    private val MP4_MDIA = boxType("mdia")
    private val MP4_MDHD = boxType("mdhd")
    private val MP4_HDLR = boxType("hdlr")
    private val MP4_MINF = boxType("minf")
    private val MP4_STBL = boxType("stbl")
    private val MP4_STSD = boxType("stsd")
    private val MP4_UDTA = boxType("udta")
    private val MP4_NAME = boxType("name")

    private fun readMp4(file: File): Mp4Moov? {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            var pos = 0L
            while (pos + 8 <= length) {
                raf.seek(pos)
                var size = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = raf.readInt().toLong() and 0xFFFFFFFFL
                var header = 8L
                if (size == 1L) {
                    size = raf.readLong()
                    header = 16L
                } else if (size == 0L) {
                    size = length - pos
                }
                if (size < header) return null
                if (type == MP4_MOOV) {
                    val payload = size - header
                    if (payload > MAX_MP4_MOOV_BYTES || pos + size > length) return null
                    val data = ByteArray(payload.toInt())
                    raf.readFully(data)
                    return Mp4Moov(pos + header, data)
                }
                pos += size
            }
            return null
        }
    }

    /** Parses consecutive boxes in buf[from, to). Returns children with header offset. */
    private fun mp4Children(buf: ByteArray, from: Int, to: Int): List<Pair<Child, Int>> {
        val result = mutableListOf<Pair<Child, Int>>()
        var p = from
        while (p + 8 <= to) {
            var size = readUInt(buf, p, 4)
            val type = readUInt(buf, p + 4, 4)
            var header = 8
            if (size == 1L) {
                if (p + 16 > to) break
                size = readUInt(buf, p + 8, 8)
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < header || p + size > to) break
            result.add(Child(type, p, (p + size).toInt()) to header)
            p += size.toInt()
        }
        return result
    }

    private fun mp4Find(buf: ByteArray, parent: Pair<Child, Int>, type: Long): Pair<Child, Int>? =
        mp4Children(buf, parent.first.start + parent.second, parent.first.end).firstOrNull { it.first.id == type }

    private fun parseMp4Traks(moov: Mp4Moov): Pair<List<Child>, List<Mp4Trak>> {
        val data = moov.data
        val boxes = mp4Children(data, 0, data.size)
        val traks = boxes.filter { it.first.id == MP4_TRAK }.map { trak ->
            val start = trak.first.start
            var trackId = 0L
            var flagsRel: Int? = null
            var enabled = true
            mp4Find(data, trak, MP4_TKHD)?.let { (tkhd, h) ->
                val p = tkhd.start + h
                if (p + 24 <= tkhd.end) {
                    val version = data[p].toInt()
                    flagsRel = p + 3 - start // low byte of the 24-bit flags
                    enabled = data[p + 3].toInt() and 0x01 != 0
                    trackId = readUInt(data, if (version == 1) p + 20 else p + 12, 4)
                }
            }

            var handler: String? = null
            var handlerName: String? = null
            var language: String? = null
            var codec: String? = null
            var channels: Int? = null
            var sampleRate: Int? = null
            mp4Find(data, trak, MP4_MDIA)?.let { mdia ->
                mp4Find(data, mdia, MP4_HDLR)?.let { (hdlr, h) ->
                    val p = hdlr.start + h
                    if (p + 12 <= hdlr.end) handler = fourCC(readUInt(data, p + 8, 4))
                    if (p + 24 < hdlr.end) {
                        // ISO: null-terminated UTF-8. QuickTime: Pascal string (length byte first).
                        var nameStart = p + 24
                        var nameEnd = hdlr.end
                        val first = data[nameStart].toInt() and 0xFF
                        if (first in 1..(hdlr.end - nameStart - 1) && first == hdlr.end - nameStart - 1) {
                            nameStart += 1
                        }
                        val nul = (nameStart until nameEnd).firstOrNull { data[it].toInt() == 0 }
                        if (nul != null) nameEnd = nul
                        handlerName = String(data, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8).trim()
                    }
                }
                mp4Find(data, mdia, MP4_MDHD)?.let { (mdhd, h) ->
                    val p = mdhd.start + h
                    val langOff = if (data[p].toInt() == 1) p + 32 else p + 20
                    if (langOff + 2 <= mdhd.end) language = mp4Language(readUInt(data, langOff, 2).toInt())
                }
                val stsd = mp4Find(data, mdia, MP4_MINF)
                    ?.let { mp4Find(data, it, MP4_STBL) }
                    ?.let { mp4Find(data, it, MP4_STSD) }
                stsd?.let { (box, h) ->
                    // stsd: version/flags(4) entry_count(4), then sample entries
                    val entry = mp4Children(data, box.start + h + 8, box.end).firstOrNull()
                    entry?.let { (e, eh) ->
                        codec = fourCC(e.id)
                        // AudioSampleEntry: reserved(6) data_ref(2) version(2) rev(2) vendor(4)
                        //                   channelcount(2) samplesize(2) predef(2) reserved(2) samplerate(4, 16.16)
                        val p = e.start + eh
                        if (p + 28 <= e.end) {
                            channels = readUInt(data, p + 16, 2).toInt()
                            sampleRate = (readUInt(data, p + 24, 4) ushr 16).toInt()
                        }
                    }
                }
            }
            // Track title as written by ffmpeg/MP4Box: trak/udta/name holding a plain string
            val title = mp4Find(data, trak, MP4_UDTA)
                ?.let { mp4Find(data, it, MP4_NAME) }
                ?.let { (box, h) -> String(data, box.start + h, box.end - box.start - h, StandardCharsets.UTF_8) }
                ?.trimEnd('\u0000')?.trim()
                ?.takeIf { it.isNotEmpty() }
            Mp4Trak(trak.first, trackId, handler, handlerName, title, language, codec, channels, sampleRate, flagsRel, enabled)
        }
        return boxes.map { it.first } to traks
    }

    /** ISO-639-2/T packed as three 5-bit letters. QuickTime may store Mac language codes instead. */
    private fun mp4Language(packed: Int): String? {
        if (packed == 0 || packed == 0x7FFF) return null
        if (packed < 0x400) return null
        val chars = charArrayOf(
            ((packed shr 10 and 0x1F) + 0x60).toChar(),
            ((packed shr 5 and 0x1F) + 0x60).toChar(),
            ((packed and 0x1F) + 0x60).toChar()
        )
        return String(chars).takeIf { it.all { c -> c in 'a'..'z' } }
    }

    private fun Mp4Trak.isAudio() = handler == "soun"

    private val GENERIC_HANDLER_NAMES = setOf(
        "soundhandler", "sound handler", "core media audio", "apple sound media handler",
        "isomedia file produced by google", "mp4 sound handler", "l-smash audio handler", "gpac isom audio handler"
    )

    private fun mp4AudioTracks(moov: Mp4Moov): List<AudioTrack> {
        val audio = parseMp4Traks(moov).second.filter { it.isAudio() }
        val defaultOrdinal = audio.indexOfFirst { it.enabled }.coerceAtLeast(0)
        return audio.mapIndexed { i, t ->
            AudioTrack(
                index = i,
                trackId = t.trackId,
                codec = t.codec,
                language = t.language,
                title = t.title ?: t.handlerName?.takeIf {
                    it.isNotBlank() && it.lowercase(Locale.ROOT) !in GENERIC_HANDLER_NAMES &&
                        !it.lowercase(Locale.ROOT).contains("handler")
                },
                channels = t.channels,
                sampleRate = t.sampleRate,
                isDefault = i == defaultOrdinal
            )
        }
    }

    private fun patchMp4(moov: Mp4Moov, chosen: Int): BytePatch? {
        val (children, traks) = parseMp4Traks(moov)
        if (children.isEmpty()) return null
        val audio = traks.filter { it.isAudio() }
        if (chosen !in audio.indices) return null
        val audioSlots = audio.map { t -> children.indexOf(t.child) }

        val out = permuteAudioEntries(moov.data, children, audioSlots, chosen) { buf, newStart, ordinal ->
            val rel = audio[ordinal].tkhdFlagsRel ?: return@permuteAudioEntries
            val at = newStart + rel
            val flags = buf[at].toInt()
            buf[at] = (if (ordinal == chosen) flags or 0x01 else flags and 0x01.inv()).toByte()
        }
        return BytePatch(moov.fileOffset, out)
    }
}
