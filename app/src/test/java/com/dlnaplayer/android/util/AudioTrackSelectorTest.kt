package com.dlnaplayer.android.util

import com.dlnaplayer.android.server.PatchedFileInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32

class AudioTrackSelectorTest {

    // ---------------------------------------------------------------- Matroska

    @Test
    fun mkvListsAudioTracksInHeaderOrder() {
        val file = tempFile("mkv", buildMkv())
        val scan = AudioTrackSelector.scan(file)

        assertTrue(scan.canSwitch)
        assertEquals(2, scan.tracks.size)
        val eng = scan.tracks[0]
        assertEquals(2L, eng.trackId)
        assertEquals("eng", eng.language)
        assertTrue(eng.isDefault)
        val jpn = scan.tracks[1]
        assertEquals(3L, jpn.trackId)
        assertEquals("jpn", jpn.language)
        assertEquals("Japanese", jpn.title)
        assertEquals(6, jpn.channels)
        assertEquals(48000, jpn.sampleRate)
        assertFalse(jpn.isDefault)
    }

    @Test
    fun mkvPatchMakesChosenTrackFirstAndDefault() {
        val original = buildMkv()
        val file = tempFile("mkv", original)
        val jpn = AudioTrackSelector.scan(file).tracks[1]

        val patch = AudioTrackSelector.buildPatch(file, jpn)!!
        val patched = applyPatch(original, patch)
        assertEquals(original.size, patched.size)

        val rescan = AudioTrackSelector.scan(tempFile("mkv", patched))
        assertEquals(listOf(3L, 2L), rescan.tracks.map { it.trackId })
        assertTrue(rescan.tracks[0].isDefault)
        assertEquals("jpn", rescan.tracks[0].language)
        assertEquals(0L, flagDefaultOf(patched, patch, trackNumber = 2))

        // Bytes outside the patch (clusters) are untouched
        assertArrayEquals(original.copyOfRange(patch.end.toInt(), original.size), patched.copyOfRange(patch.end.toInt(), patched.size))
        assertCrcValid(patched, patch)
    }

    @Test
    fun mkvPatchClearsImplicitDefaultOnOtherTracks() {
        // ffmpeg omits FlagDefault when it is 1, so "eng" is default without having the element
        val original = buildMkv(explicitEngDefault = false)
        val file = tempFile("mkv", original)
        val patch = AudioTrackSelector.buildPatch(file, AudioTrackSelector.scan(file).tracks[1])!!
        val patched = applyPatch(original, patch)

        assertEquals(original.size, patched.size)
        assertEquals(0L, flagDefaultOf(patched, patch, trackNumber = 2))
        assertEquals(null, flagDefaultOf(patched, patch, trackNumber = 3)) // absent = default
        assertEquals(listOf(3L, 2L), AudioTrackSelector.scan(tempFile("mkv", patched)).tracks.map { it.trackId })
        assertCrcValid(patched, patch)
    }

    @Test
    fun mkvPatchBackToOriginalTrackRestoresOrder() {
        val original = buildMkv()
        val file = tempFile("mkv", original)
        val patched = applyPatch(original, AudioTrackSelector.buildPatch(file, AudioTrackSelector.scan(file).tracks[1])!!)
        val patchedFile = tempFile("mkv", patched)
        val engInPatched = AudioTrackSelector.scan(patchedFile).tracks.first { it.language == "eng" }

        val restored = applyPatch(patched, AudioTrackSelector.buildPatch(patchedFile, engInPatched)!!)
        assertEquals(listOf(2L, 3L), AudioTrackSelector.scan(tempFile("mkv", restored)).tracks.map { it.trackId })
    }

    // ---------------------------------------------------------------- MP4

    @Test
    fun mp4ListsAudioTracks() {
        val scan = AudioTrackSelector.scan(tempFile("mp4", buildMp4()))

        assertTrue(scan.canSwitch)
        assertEquals(2, scan.tracks.size)
        assertEquals("eng", scan.tracks[0].language)
        assertEquals("Main Mix", scan.tracks[0].title)
        assertEquals("mp4a", scan.tracks[0].codec)
        assertEquals(2, scan.tracks[0].channels)
        assertEquals(48000, scan.tracks[0].sampleRate)
        assertTrue(scan.tracks[0].isDefault)
        assertEquals("fra", scan.tracks[1].language)
        assertEquals("ac-3", scan.tracks[1].codec)
        assertEquals("French Commentary", scan.tracks[1].title)
        assertEquals(6, scan.tracks[1].channels)
    }

    @Test
    fun mp4PatchReordersTraksAndTogglesEnabledFlag() {
        val original = buildMp4()
        val file = tempFile("mp4", original)
        val fra = AudioTrackSelector.scan(file).tracks[1]

        val patch = AudioTrackSelector.buildPatch(file, fra)!!
        val patched = applyPatch(original, patch)
        assertEquals(original.size, patched.size)

        val rescan = AudioTrackSelector.scan(tempFile("mp4", patched))
        assertEquals(listOf(3L, 2L), rescan.tracks.map { it.trackId })
        assertTrue(rescan.tracks[0].isDefault)
        assertFalse(rescan.tracks[1].isDefault)

        // mdat (after moov) untouched
        val mdatStart = original.size - MDAT_PAYLOAD.size
        assertArrayEquals(MDAT_PAYLOAD, patched.copyOfRange(mdatStart, patched.size))
    }

    @Test
    fun unsupportedContainerIsNotSwitchable() {
        val scan = AudioTrackSelector.scan(tempFile("avi", ByteArray(64)))
        assertFalse(scan.canSwitch)
        assertTrue(scan.tracks.isEmpty())
    }

    // ---------------------------------------------------------------- Patched stream

    @Test
    fun patchedStreamOverlaysPatchForAnyRange() {
        val original = ByteArray(5000) { (it % 251).toByte() }
        val file = tempFile("bin", original)
        val patch = BytePatch(1200, ByteArray(700) { 0x7F })
        val expected = applyPatch(original, patch)

        for ((start, len) in listOf(0L to 5000L, 0L to 1300L, 1250L to 100L, 1800L to 400L, 1899L to 1L, 3000L to 2000L)) {
            val read = PatchedFileInputStream(file, start, len, patch).use { it.readBytes() }
            assertArrayEquals("range $start+$len", expected.copyOfRange(start.toInt(), (start + len).toInt()), read)
        }
    }

    // ---------------------------------------------------------------- Builders

    private fun tempFile(ext: String, bytes: ByteArray): File =
        File.createTempFile("audiotrack", ".$ext").apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    private fun applyPatch(original: ByteArray, patch: BytePatch): ByteArray =
        original.copyOf().also { System.arraycopy(patch.bytes, 0, it, patch.offset.toInt(), patch.bytes.size) }

    private fun assertCrcValid(file: ByteArray, patch: BytePatch) {
        // Tracks payload starts with CRC-32 element: BF 84 <4 bytes LE>
        val p = patch.offset.toInt()
        assertEquals(0xBF, file[p].toInt() and 0xFF)
        val stored = ByteBuffer.wrap(file, p + 2, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val crc = CRC32().apply { update(file, p + 6, patch.bytes.size - 6) }.value
        assertEquals(crc, stored)
    }

    /** Finds the FlagDefault value of a TrackEntry by scanning the patched Tracks bytes. */
    private fun flagDefaultOf(file: ByteArray, patch: BytePatch, trackNumber: Int): Long? {
        val tracks = file.copyOfRange(patch.offset.toInt(), patch.end.toInt())
        var p = 6 // skip CRC-32 element
        while (p < tracks.size) {
            val id = tracks[p].toInt() and 0xFF
            val size = tracks[p + 1].toInt() and 0x7F // test entries use 1-byte sizes
            val body = tracks.copyOfRange(p + 2, p + 2 + size)
            if (id == 0xAE) {
                var q = 0
                var number = -1
                var flag: Long? = null
                while (q < body.size) {
                    val fid = if (body[q].toInt() and 0x80 != 0) body[q].toInt() and 0xFF else -1
                    val idLen = if (fid >= 0) 1 else if (body[q].toInt() and 0x40 != 0) 2 else 3
                    val fsize = body[q + idLen].toInt() and 0x7F
                    val v = body[q + idLen + 1].toLong() and 0xFF
                    if (fid == 0xD7) number = v.toInt()
                    if (fid == 0x88) flag = v
                    q += idLen + 1 + fsize
                }
                if (number == trackNumber) return flag
            }
            p += 2 + size
        }
        return null
    }

    private fun idBytes(id: Long): ByteArray {
        val len = when {
            id >= 0x1000000 -> 4
            id >= 0x10000 -> 3
            id >= 0x100 -> 2
            else -> 1
        }
        return ByteArray(len) { i -> (id ushr (8 * (len - 1 - i))).toByte() }
    }

    private fun sizeBytes(size: Int): ByteArray = when {
        size < 0x7F -> byteArrayOf((0x80 or size).toByte())
        size < 0x3FFF -> byteArrayOf((0x40 or (size shr 8)).toByte(), size.toByte())
        else -> byteArrayOf(0x10, (size shr 16).toByte(), (size shr 8).toByte(), size.toByte())
    }

    private fun el(id: Long, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, b -> acc + b }
        return idBytes(id) + sizeBytes(body.size) + body
    }

    private fun uintEl(id: Long, v: Long) = el(id, byteArrayOf(v.toByte()))
    private fun strEl(id: Long, s: String) = el(id, s.toByteArray())
    private fun floatEl(id: Long, f: Float) = el(id, ByteBuffer.allocate(4).putFloat(f).array())

    private fun buildMkv(explicitEngDefault: Boolean = true): ByteArray {
        val header = el(0x1A45DFA3, strEl(0x4282, "matroska"))
        val info = el(0x1549A966, uintEl(0x2AD7B1, 1))
        val video = el(0xAE, uintEl(0xD7, 1), uintEl(0x83, 1), strEl(0x86, "V_MPEG4/ISO/AVC"))
        val eng = el(
            0xAE, uintEl(0xD7, 2), uintEl(0x83, 2),
            if (explicitEngDefault) uintEl(0x88, 1) else ByteArray(0), strEl(0x86, "A_AAC"),
            strEl(0x22B59C, "eng"), el(0xE1, uintEl(0x9F, 2), floatEl(0xB5, 44100f))
        )
        val jpn = el(
            0xAE, uintEl(0xD7, 3), uintEl(0x83, 2), uintEl(0x88, 0), strEl(0x86, "A_AC3"),
            strEl(0x536E, "Japanese"), strEl(0x22B59C, "jpn"), el(0xE1, uintEl(0x9F, 6), floatEl(0xB5, 48000f))
        )
        val sub = el(0xAE, uintEl(0xD7, 4), uintEl(0x83, 17), strEl(0x86, "S_TEXT/UTF8"))
        val entries = video + eng + jpn + sub
        val crc = CRC32().apply { update(entries) }.value
        val crcEl = byteArrayOf(0xBF.toByte(), 0x84.toByte()) + ByteArray(4) { i -> (crc ushr (8 * i)).toByte() }
        val tracks = el(0x1654AE6B, crcEl, entries)
        val cluster = el(0x1F43B675, uintEl(0xE7, 0), el(0xA3, ByteArray(50) { 0x55 }))
        // Segment with "unknown" size, as written by live muxers
        val segment = idBytes(0x18538067) + byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1) + info + tracks + cluster
        return header + segment
    }

    private val MDAT_PAYLOAD = ByteArray(200) { (it * 7).toByte() }

    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, b -> acc + b }
        return ByteBuffer.allocate(8).putInt(8 + body.size).put(type.toByteArray()).array() + body
    }

    private fun tkhd(trackId: Int, enabled: Boolean): ByteArray {
        val p = ByteBuffer.allocate(84)
        p.putInt(if (enabled) 0x000003 else 0x000002) // version 0 + flags
        p.putInt(0).putInt(0).putInt(trackId)
        return box("tkhd", p.array())
    }

    private fun mdhd(lang: String): ByteArray {
        val packed = lang.fold(0) { acc, c -> (acc shl 5) or (c.code - 0x60) }
        val p = ByteBuffer.allocate(24).putInt(0).putInt(0).putInt(0).putInt(1000).putInt(0).putShort(packed.toShort())
        return box("mdhd", p.array())
    }

    private fun hdlr(type: String, name: String): ByteArray =
        box("hdlr", ByteArray(8) + type.toByteArray() + ByteArray(12) + name.toByteArray() + byteArrayOf(0))

    private fun audioEntry(codec: String, channels: Int, rate: Int): ByteArray {
        val p = ByteBuffer.allocate(28)
        p.position(16)
        p.putShort(channels.toShort()).putShort(16).putShort(0).putShort(0).putInt(rate shl 16)
        return box(codec, p.array())
    }

    private fun audioTrak(
        id: Int, enabled: Boolean, lang: String, name: String, codec: String, ch: Int, title: String? = null
    ): ByteArray =
        box(
            "trak", tkhd(id, enabled),
            title?.let { box("udta", box("name", it.toByteArray())) } ?: ByteArray(0),
            box(
                "mdia", mdhd(lang), hdlr("soun", name),
                box("minf", box("stbl", box("stsd", ByteArray(4) + byteArrayOf(0, 0, 0, 1) + audioEntry(codec, ch, 48000))))
            )
        )

    private fun buildMp4(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", "isom".toByteArray(), ByteArray(4), "isommp41".toByteArray()))
        out.write(
            box(
                "moov",
                box("mvhd", ByteArray(100)),
                box("trak", tkhd(1, true), box("mdia", mdhd("und"), hdlr("vide", "VideoHandler"))),
                audioTrak(2, true, "eng", "SoundHandler", "mp4a", 2, title = "Main Mix"),
                box("udta", ByteArray(12)),
                audioTrak(3, false, "fra", "French Commentary", "ac-3", 6)
            )
        )
        out.write(box("mdat", MDAT_PAYLOAD))
        return out.toByteArray()
    }
}
