package com.jondude1.bookshelf.reader

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * Minimal MOBI / AZW3 reader for DRM-free files. Decompresses the PalmDOC text,
 * rewires image references, and splits the book into chapters on page breaks.
 */
class MobiBook(file: File) : ReflowBook {
    override val title: String
    override val chapters: List<Chapter>
    override val toc: List<TocEntry>
    private val html = HashMap<String, String>()
    private val images = HashMap<Int, ByteArray>()

    init {
        val data = file.readBytes()
        if (data.size < 78) throw IllegalStateException("File is too small to be a MOBI book")
        val numRecords = u16(data, 76)
        val offsets = IntArray(numRecords) { u32(data, 78 + it * 8).toInt() }
        fun record(i: Int): ByteArray {
            val start = offsets[i]
            val end = if (i + 1 < numRecords) offsets[i + 1] else data.size
            return data.copyOfRange(start, end.coerceAtLeast(start))
        }

        val r0 = record(0)
        val compression = u16(r0, 0)
        val textRecordCount = u16(r0, 8)
        val encryption = u16(r0, 12)
        if (encryption != 0) throw IllegalStateException("This book is DRM-protected, so it can't be opened. Remove DRM or convert it to EPUB in Calibre.")
        if (compression == 17480) throw IllegalStateException("This MOBI uses HUFF/CDIC compression, which BookShelf can't read yet. Convert it to EPUB in Calibre.")

        val isMobi = r0.size > 20 && String(r0, 16, 4, Charsets.US_ASCII) == "MOBI"
        val headerLen = if (isMobi) u32(r0, 20).toInt() else 0
        val encoding = if (isMobi) u32(r0, 28).toInt() else 1252
        val charset: Charset = if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        val firstImage = if (isMobi && headerLen >= 0x6C - 16 + 4) u32(r0, 0x6C).toInt() else -1
        val extraFlags = if (isMobi && headerLen >= 0xE4) u16(r0, 0xF2) else 0
        val exth = if (isMobi && (u32(r0, 0x80) and 0x40L) != 0L) parseExth(r0, 16 + headerLen) else emptyMap()

        title = (exth[503] ?: run {
            val off = if (isMobi) u32(r0, 0x54).toInt() else 0
            val len = if (isMobi) u32(r0, 0x58).toInt() else 0
            if (off > 0 && off + len <= r0.size) String(r0, off, len, charset) else null
        } ?: String(data, 0, 32, Charsets.US_ASCII).trim('\u0000', ' ')).trim()

        // Decompress text
        val out = ByteArrayOutputStream()
        for (i in 1..textRecordCount.coerceAtMost(numRecords - 1)) {
            val rec = record(i)
            val trimmed = rec.copyOfRange(0, (rec.size - trailingSize(rec, extraFlags)).coerceAtLeast(0))
            out.write(if (compression == 2) palmDoc(trimmed) else trimmed)
        }
        var text = String(out.toByteArray(), charset)

        // Images: records from firstImage onward that look like images
        if (firstImage in 1 until numRecords) {
            for (i in firstImage until numRecords) {
                val rec = record(i)
                if (sniffImageMime(rec) != null) images[i - firstImage + 1] = rec
            }
        }
        // MOBI6: <img recindex="00012"> ; KF8: src="kindle:embed:000C?mime=..."
        text = Regex("""recindex\s*=\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE).replace(text) {
            "src=\"img/${it.groupValues[1].toInt()}\""
        }
        text = Regex("""kindle:embed:([0-9A-Va-v]{4})(\?[^"')\s]*)?""").replace(text) {
            "img/${it.groupValues[1].toInt(32)}"
        }
        // Remove KF8 flow/position links that the WebView can't resolve
        text = text.replace(Regex("""(?i)(href|src)\s*=\s*["']kindle:(pos|flow)[^"']*["']"""), "")

        // Split into chapters
        val parts = splitChapters(text)
        val chs = ArrayList<Chapter>()
        val tocList = ArrayList<TocEntry>()
        parts.forEachIndexed { i, body ->
            val path = "chapter_$i.html"
            val heading = Regex("""(?is)<h[1-3][^>]*>(.*?)</h[1-3]>""").find(body)?.groupValues?.get(1)?.let { htmlToText(it) }
                ?.takeIf { it.isNotBlank() && it.length < 120 }
            html[path] = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>${heading ?: ""}</title></head><body>$body</body></html>"
            chs += Chapter(path, heading, body.length.toLong())
            if (heading != null) tocList += TocEntry(heading, path, null, 0, i)
        }
        chapters = chs
        toc = tocList.ifEmpty { chs.mapIndexed { i, c -> TocEntry("Section ${i + 1}", c.path, null, 0, i) } }
    }

    override fun resource(path: String): Resource? {
        html[path]?.let { return Resource(it.toByteArray(Charsets.UTF_8), "text/html") }
        if (path.startsWith("img/")) {
            val n = path.removePrefix("img/").toIntOrNull() ?: return null
            val b = images[n] ?: return null
            return Resource(b, sniffImageMime(b) ?: "image/jpeg")
        }
        return null
    }

    override fun close() { html.clear(); images.clear() }

    private fun splitChapters(text: String): List<String> {
        // Strip outer document wrappers; keep body content
        val bodyish = text.replace(Regex("(?is)<\\?xml[^>]*>|<!DOCTYPE[^>]*>"), "")
            .replace(Regex("(?is)<head>.*?</head>"), "")
            .replace(Regex("(?i)</?(html|body)[^>]*>"), "")
        val pieces = bodyish.split(Regex("(?i)<mbp:pagebreak\\s*/?>|<div[^>]*page-break-before\\s*:\\s*always[^>]*>")).map { it.trim() }
            .filter { htmlToText(it).isNotBlank() || it.contains("<img", true) }
        // Merge tiny fragments, split giants so pagination stays quick
        val merged = ArrayList<String>()
        val sb = StringBuilder()
        for (p in pieces) {
            sb.append(p).append('\n')
            if (sb.length > 6_000) { merged += sb.toString(); sb.clear() }
        }
        if (sb.isNotBlank()) merged += sb.toString()
        return merged.flatMap { chunk ->
            if (chunk.length < 250_000) listOf(chunk) else {
                val list = ArrayList<String>(); var start = 0
                while (start < chunk.length) {
                    var end = (start + 200_000).coerceAtMost(chunk.length)
                    if (end < chunk.length) { val pEnd = chunk.indexOf("</p>", end, true); if (pEnd > 0) end = pEnd + 4 }
                    list += chunk.substring(start, end); start = end
                }
                list
            }
        }.ifEmpty { listOf("<p>(This book appears to be empty.)</p>") }
    }

    private fun parseExth(r0: ByteArray, start: Int): Map<Int, String> {
        if (start + 12 > r0.size || String(r0, start, 4, Charsets.US_ASCII) != "EXTH") return emptyMap()
        val count = u32(r0, start + 8).toInt()
        var p = start + 12
        val out = HashMap<Int, String>()
        repeat(count) {
            if (p + 8 > r0.size) return out
            val type = u32(r0, p).toInt()
            val len = u32(r0, p + 4).toInt()
            if (len < 8 || p + len > r0.size) return out
            if (type == 100 || type == 503) out[type] = String(r0, p + 8, len - 8, Charsets.UTF_8)
            p += len
        }
        return out
    }

    companion object {
        fun u16(b: ByteArray, o: Int) = if (o + 1 < b.size) ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF) else 0
        fun u32(b: ByteArray, o: Int): Long = if (o + 3 < b.size)
            ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
                ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF) else 0

        fun trailingSize(rec: ByteArray, flags: Int): Int {
            var num = 0
            var f = flags shr 1
            while (f != 0) {
                if (f and 1 != 0) {
                    val end = rec.size - num
                    var result = 0; var shift = 0
                    var i = end - 1
                    while (i >= 0 && i >= end - 4) {
                        val v = rec[i].toInt() and 0xFF
                        result = result or ((v and 0x7F) shl shift)
                        shift += 7
                        if (v and 0x80 != 0) break
                        i--
                    }
                    num += result
                }
                f = f shr 1
            }
            if (flags and 1 != 0 && rec.size - num - 1 >= 0) num += (rec[rec.size - num - 1].toInt() and 0x3) + 1
            return num.coerceIn(0, rec.size)
        }

        fun palmDoc(src: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(src.size * 2)
            var buf = ByteArray(8192); var len = 0
            fun put(b: Int) { if (len == buf.size) buf = buf.copyOf(buf.size * 2); buf[len++] = b.toByte() }
            var i = 0
            while (i < src.size) {
                val c = src[i++].toInt() and 0xFF
                when {
                    c == 0 || c in 0x09..0x7F -> put(c)
                    c in 0x01..0x08 -> repeat(c) { if (i < src.size) put(src[i++].toInt() and 0xFF) }
                    c in 0x80..0xBF -> {
                        if (i >= src.size) break
                        val pair = (c shl 8) or (src[i++].toInt() and 0xFF)
                        val dist = (pair shr 3) and 0x7FF
                        val n = (pair and 7) + 3
                        if (dist in 1..len) repeat(n) { put(buf[len - dist].toInt() and 0xFF) }
                    }
                    else -> { put(' '.code); put(c xor 0x80) }
                }
            }
            out.write(buf, 0, len)
            return out.toByteArray()
        }
    }
}
