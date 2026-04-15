package com.example.starbucknotetaker

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal `.tar.gz` extractor that uses only the standard library
 * (`java.util.zip.GZIPInputStream`) without requiring Apache Commons Compress.
 *
 * Handles the POSIX ustar format produced by `mlc_llm compile --target android`.
 * Only regular files and directories are materialised; symlinks are silently skipped.
 */
object TarExtractor {

    private const val TAG = "TarExtractor"

    /** Size of a single tar header block, in bytes. */
    private const val BLOCK = 512

    /**
     * Extracts all entries from the gzip-compressed tar stream [input] into [destDir].
     *
     * @param input   Raw (compressed) input stream for the `.tar.gz`/`.tar` file.
     *                May be gzip-compressed; the function detects the magic bytes
     *                automatically.
     * @param destDir Destination directory.  Created if it does not exist.
     */
    fun extract(input: InputStream, destDir: File) {
        destDir.mkdirs()

        // Wrap with GZIP if the stream starts with the GZIP magic bytes.
        val raw = input.buffered()
        raw.mark(2)
        val b0 = raw.read()
        val b1 = raw.read()
        raw.reset()
        val stream: InputStream =
            if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw) else raw

        stream.use { src ->
            val header = ByteArray(BLOCK)
            while (true) {
                // Read the 512-byte header block
                val read = src.readNBytes(header, 0, BLOCK)
                if (read < BLOCK) break

                // An all-zero block signals the end of archive
                if (header.all { it == 0.toByte() }) break

                val name   = header.cStr(0, 100)
                val sizeOct = header.cStr(124, 12).trim()
                val typeFlag = header[156].toInt().toChar()

                val size = if (sizeOct.isEmpty()) 0L else sizeOct.toLong(8)
                val paddedSize = if (size == 0L) 0L else ((size + BLOCK - 1) / BLOCK) * BLOCK

                // ustar prefix for long paths (bytes 157–256 in some implementations
                // are at offset 345 in ustar).  We support both plain and ustar.
                val prefix = header.cStr(345, 155).trim('/')
                val fullName = if (prefix.isEmpty()) name else "$prefix/$name"

                val dest = File(destDir, fullName).canonicalFile
                // Guard against path-traversal attacks
                if (!dest.canonicalPath.startsWith(destDir.canonicalPath + File.separator) &&
                    dest.canonicalPath != destDir.canonicalPath
                ) {
                    Log.w(TAG, "Skipping path-traversal entry: $fullName")
                    src.skipFully(paddedSize)
                    continue
                }

                when (typeFlag) {
                    '5' -> {
                        // Directory entry
                        dest.mkdirs()
                        src.skipFully(paddedSize)
                    }
                    '0', '\u0000' -> {
                        // Regular file
                        dest.parentFile?.mkdirs()
                        dest.outputStream().buffered().use { out ->
                            src.copyExact(out, size)
                        }
                        // Skip any padding bytes to align to the next 512-byte boundary
                        val padding = (paddedSize - size).toInt()
                        if (padding > 0) src.skipFully(padding.toLong())
                        Log.d(TAG, "Extracted: $fullName (${size} bytes) → ${dest.absolutePath}")
                    }
                    else -> {
                        // Symlinks, hard links, etc. — skip the data block
                        src.skipFully(paddedSize)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // ByteArray helpers
    // ------------------------------------------------------------------

    /** Reads a null-terminated C string from [this] ByteArray at [offset] with max [len] bytes. */
    private fun ByteArray.cStr(offset: Int, len: Int): String {
        val end = (offset until minOf(offset + len, size)).firstOrNull { this[it] == 0.toByte() }
            ?: (offset + len).coerceAtMost(size)
        return String(this, offset, end - offset, Charsets.US_ASCII).trim()
    }

    // ------------------------------------------------------------------
    // InputStream helpers
    // ------------------------------------------------------------------

    /** Reads exactly [n] bytes from the stream into [buf] starting at [off]. */
    private fun InputStream.readNBytes(buf: ByteArray, off: Int, n: Int): Int {
        var total = 0
        while (total < n) {
            val r = read(buf, off + total, n - total)
            if (r == -1) break
            total += r
        }
        return total
    }

    /** Skips exactly [n] bytes, reading and discarding as needed. */
    private fun InputStream.skipFully(n: Long) {
        var remaining = n
        val discard = ByteArray(BLOCK)
        while (remaining > 0) {
            val r = read(discard, 0, minOf(remaining, BLOCK.toLong()).toInt())
            if (r == -1) break
            remaining -= r
        }
    }

    /** Copies exactly [n] bytes from this stream into [out]. */
    private fun InputStream.copyExact(out: OutputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(8 * 1024)
        while (remaining > 0) {
            val r = read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (r == -1) break
            out.write(buf, 0, r)
            remaining -= r
        }
    }
}
