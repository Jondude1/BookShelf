package com.jondude1.bookshelf.reader

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

class EpubBook(file: File) : ReflowBook {
    private val zip = ZipFile(file)
    private val entries: Map<String, String> // lower-case name -> real name
    override val title: String
    override val chapters: List<Chapter>
    override val toc: List<TocEntry>

    private data class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: String)

    init {
        entries = zip.entries().asSequence().associate { it.name.lowercase() to it.name }
        val opfPath = findOpfPath()
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = readText(opfPath) ?: throw IllegalStateException("This EPUB has no package file")

        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = ArrayList<String>()
        var tocId: String? = null
        var bookTitle: String? = null

        parseXml(opf) { p ->
            when (p.localName()) {
                "title" -> if (bookTitle == null) bookTitle = runCatching { p.nextText() }.getOrNull()?.trim()
                "item" -> {
                    val id = p.attr("id") ?: return@parseXml
                    val href = p.attr("href") ?: return@parseXml
                    manifest[id] = ManifestItem(id, resolve(opfDir, href), p.attr("media-type") ?: "", p.attr("properties") ?: "")
                }
                "spine" -> tocId = p.attr("toc")
                "itemref" -> p.attr("idref")?.let { spine += it }
            }
        }

        title = bookTitle?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        chapters = spine.mapNotNull { manifest[it] }
            .filter { it.mediaType.contains("html") || it.href.endsWith("htm", true) || it.href.endsWith("html", true) }
            .map { Chapter(it.href, null, entrySize(it.href)) }
            .ifEmpty { throw IllegalStateException("This EPUB has no readable chapters") }

        val navItem = manifest.values.firstOrNull { it.properties.split(' ').contains("nav") }
        val ncxItem = tocId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        val parsed = runCatching { navItem?.let { parseNav(it.href) } }.getOrNull().orEmpty()
            .ifEmpty { runCatching { ncxItem?.let { parseNcx(it.href) } }.getOrNull().orEmpty() }
        toc = parsed.ifEmpty {
            chapters.mapIndexed { i, c -> TocEntry("Section ${i + 1}", c.path, null, 0, i) }
        }
    }

    override fun resource(path: String): Resource? {
        val real = entries[path.lowercase()] ?: return null
        val e = zip.getEntry(real) ?: return null
        val bytes = zip.getInputStream(e).use { it.readBytes() }
        val mime = mimeFor(real).let { if (it == "application/octet-stream") sniffImageMime(bytes) ?: it else it }
        return Resource(bytes, mime)
    }

    override fun close() = zip.close()

    // ---------- helpers ----------

    private fun entrySize(path: String): Long = entries[path.lowercase()]?.let { zip.getEntry(it)?.size } ?: 1L

    private fun readText(path: String): String? =
        entries[path.lowercase()]?.let { zip.getEntry(it) }?.let { zip.getInputStream(it).use { s -> String(s.readBytes(), Charsets.UTF_8) } }

    private fun findOpfPath(): String {
        readText("META-INF/container.xml")?.let { xml ->
            Regex("""full-path\s*=\s*["']([^"']+)["']""").find(xml)?.groupValues?.get(1)?.let { return decode(it) }
        }
        return entries.values.firstOrNull { it.endsWith(".opf", true) } ?: throw IllegalStateException("Not a valid EPUB")
    }

    private fun parseXml(xml: String, onStart: (XmlPullParser) -> Unit) {
        val p = Xml.newPullParser()
        runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) onStart(p)
            ev = runCatching { p.next() }.getOrElse { XmlPullParser.END_DOCUMENT }
        }
    }

    private fun XmlPullParser.localName() = name.substringAfter(':').lowercase()
    private fun XmlPullParser.attr(n: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i).substringAfter(':').equals(n, true)) return getAttributeValue(i)
        return null
    }

    private fun splitHref(base: String, href: String): Pair<String, String?> {
        val frag = href.substringAfter('#', "").ifBlank { null }
        val path = href.substringBefore('#')
        return (if (path.isBlank()) base else resolve(base.substringBeforeLast('/', ""), path)) to frag
    }

    private fun entry(title: String, base: String, href: String, depth: Int): TocEntry? {
        val (path, frag) = splitHref(base, href)
        val idx = chapterIndexOf(path)
        if (idx < 0 || title.isBlank()) return null
        return TocEntry(title.trim().replace(Regex("\\s+"), " "), path, frag, depth, idx)
    }

    private fun parseNav(path: String): List<TocEntry> {
        val html = readText(path) ?: return emptyList()
        val navStart = Regex("""<nav[^>]*epub:type\s*=\s*["'][^"']*\btoc\b""", RegexOption.IGNORE_CASE).find(html)?.range?.first
            ?: Regex("<nav", RegexOption.IGNORE_CASE).find(html)?.range?.first ?: return emptyList()
        val navEnd = html.indexOf("</nav", navStart, ignoreCase = true).let { if (it < 0) html.length else it }
        val body = html.substring(navStart, navEnd)
        val out = ArrayList<TocEntry>()
        var depth = -1
        val tag = Regex("""<(/?)(ol|ul|a)\b([^>]*)>""", RegexOption.IGNORE_CASE)
        var i = 0
        while (true) {
            val m = tag.find(body, i) ?: break
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            when {
                (name == "ol" || name == "ul") && !closing -> depth++
                (name == "ol" || name == "ul") && closing -> depth--
                name == "a" && !closing -> {
                    val href = Regex("""href\s*=\s*["']([^"']*)["']""").find(m.groupValues[3])?.groupValues?.get(1)
                    val end = body.indexOf("</a", m.range.last, ignoreCase = true)
                    if (href != null && end > 0) {
                        val text = htmlToText(body.substring(m.range.last + 1, end))
                        entry(text, path, decode(href), depth.coerceAtLeast(0))?.let { out += it }
                    }
                }
            }
            i = m.range.last + 1
        }
        return out
    }

    private fun parseNcx(path: String): List<TocEntry> {
        val xml = readText(path) ?: return emptyList()
        val out = ArrayList<TocEntry>()
        var depth = -1
        var label: String? = null
        val p = Xml.newPullParser()
        runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        p.setInput(xml.reader())
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) when (p.localName()) {
                "navpoint" -> { depth++; label = null }
                "text" -> if (label == null) label = runCatching { p.nextText() }.getOrNull()
                "content" -> p.attr("src")?.let { src -> entry(label ?: "", path, decode(src), depth.coerceAtLeast(0))?.let { out += it } }
            } else if (ev == XmlPullParser.END_TAG && p.localName() == "navpoint") depth--
            ev = runCatching { p.next() }.getOrElse { XmlPullParser.END_DOCUMENT }
        }
        return out
    }

    companion object {
        fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

        fun resolve(dir: String, href: String): String {
            val h = decode(href.substringBefore('#'))
            if (h.startsWith("/")) return h.trimStart('/')
            val parts = (if (dir.isBlank()) emptyList() else dir.split('/')).toMutableList()
            for (seg in h.split('/')) when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
            return parts.joinToString("/")
        }
    }
}
