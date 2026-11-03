package com.jondude1.bookshelf.reader

import java.io.Closeable

data class Chapter(val path: String, val title: String?, val size: Long)

data class TocEntry(val title: String, val path: String, val fragment: String?, val depth: Int, val chapterIndex: Int)

class Resource(val bytes: ByteArray, val mime: String)

/** A book made of HTML chapters (EPUB, MOBI, AZW3) shown by the paginated web reader. */
interface ReflowBook : Closeable {
    val title: String
    val chapters: List<Chapter>
    val toc: List<TocEntry>

    /** [path] is relative to the book root, already URL-decoded. */
    fun resource(path: String): Resource?

    /** Plain text of a chapter, for in-book search. */
    fun chapterText(index: Int): String {
        val r = resource(chapters[index].path) ?: return ""
        return htmlToText(String(r.bytes, Charsets.UTF_8))
    }

    fun chapterIndexOf(path: String): Int = chapters.indexOfFirst { it.path.equals(path, ignoreCase = true) }

    /** Title of the TOC entry that contains this chapter. */
    fun chapterTitle(index: Int): String? =
        toc.lastOrNull { it.chapterIndex in 0..index }?.title ?: chapters.getOrNull(index)?.title

    /** Total-book fraction for a position inside a chapter, weighted by chapter size. */
    fun bookFraction(chapter: Int, inChapter: Double): Double {
        val total = chapters.sumOf { it.size.coerceAtLeast(1) }.toDouble()
        val before = chapters.take(chapter).sumOf { it.size.coerceAtLeast(1) }.toDouble()
        val cur = chapters.getOrNull(chapter)?.size?.coerceAtLeast(1) ?: 1
        return ((before + inChapter * cur) / total).coerceIn(0.0, 1.0)
    }

    /** Inverse of [bookFraction]. */
    fun positionFor(fraction: Double): Pair<Int, Double> {
        val total = chapters.sumOf { it.size.coerceAtLeast(1) }.toDouble()
        var target = fraction.coerceIn(0.0, 1.0) * total
        chapters.forEachIndexed { i, c ->
            val s = c.size.coerceAtLeast(1).toDouble()
            if (target <= s || i == chapters.lastIndex) return i to (target / s).coerceIn(0.0, 0.999)
            target -= s
        }
        return 0 to 0.0
    }
}

private val tagRegex = Regex("<[^>]+>")
private val styleRegex = Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>")
private val blockRegex = Regex("(?i)</?(p|div|br|h[1-6]|li|tr|blockquote|section)[^>]*>")
private val entityRegex = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
private val namedEntities = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
    "rdquo" to "”", "ldquo" to "“", "copy" to "©", "eacute" to "é", "shy" to "",
)

fun decodeEntities(s: String): String = entityRegex.replace(s) { m ->
    val v = m.groupValues[1]
    when {
        v.startsWith("#x") || v.startsWith("#X") -> v.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        v.startsWith("#") -> v.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        else -> namedEntities[v] ?: m.value
    }
}

fun htmlToText(html: String): String =
    decodeEntities(tagRegex.replace(blockRegex.replace(styleRegex.replace(html, " "), "\n"), ""))
        .replace(Regex("[ \\t\\u00A0]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n")
        .trim()

fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "xhtml", "xht", "xml" -> "application/xhtml+xml"
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "js" -> "application/javascript"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    "mp3" -> "audio/mpeg"
    "mp4" -> "video/mp4"
    else -> "application/octet-stream"
}

fun sniffImageMime(b: ByteArray): String? = when {
    b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
    b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
    b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
    b.size > 12 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() -> "image/webp"
    b.size > 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "image/bmp"
    else -> null
}
