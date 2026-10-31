package com.dlnaplayer.android.server

import com.dlnaplayer.android.util.BytePatch
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Streams [length] bytes of [file] starting at [start], substituting the bytes of [patch]
 * wherever the requested range overlaps it. Used to serve a file with a rewritten header
 * (e.g. a different default audio track) without copying it.
 */
class PatchedFileInputStream(
    file: File,
    start: Long,
    length: Long,
    private val patch: BytePatch?
) : InputStream() {

    private val input = FileInputStream(file)
    private var position = start
    private var remaining = length

    init {
        var toSkip = start
        while (toSkip > 0) {
            val skipped = input.skip(toSkip)
            if (skipped <= 0) throw IOException("Could not seek to $start")
            toSkip -= skipped
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n <= 0) return -1
        patch?.let { overlay(it, b, off, n) }
        position += n
        remaining -= n
        return n
    }

    private fun overlay(p: BytePatch, b: ByteArray, off: Int, n: Int) {
        val from = maxOf(position, p.offset)
        val to = minOf(position + n, p.end)
        if (from >= to) return
        System.arraycopy(p.bytes, (from - p.offset).toInt(), b, off + (from - position).toInt(), (to - from).toInt())
    }

    override fun available(): Int = minOf(input.available().toLong(), remaining).toInt()

    override fun close() = input.close()
}
