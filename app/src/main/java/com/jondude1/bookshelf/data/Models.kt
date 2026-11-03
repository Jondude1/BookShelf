package com.jondude1.bookshelf.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

val JsonFmt = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
    isLenient = true
}

// ---------- Audiobookshelf wire models ----------

@Serializable
data class AbsLoginResponse(val user: AbsUser, val userDefaultLibraryId: String? = null)

@Serializable
data class AbsUser(
    val id: String = "",
    val username: String = "",
    val token: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val mediaProgress: List<AbsProgress> = emptyList(),
)

@Serializable
data class AbsProgress(
    val id: String? = null,
    val libraryItemId: String = "",
    val episodeId: String? = null,
    val ebookLocation: String? = null,
    val ebookProgress: Double? = null,
    val isFinished: Boolean = false,
    val lastUpdate: Long = 0,
    val finishedAt: Long? = null,
)

@Serializable
data class AbsLibrary(val id: String, val name: String, val mediaType: String = "book", val icon: String? = null)

@Serializable
data class AbsLibraries(val libraries: List<AbsLibrary> = emptyList())

@Serializable
data class AbsItemsPage(val results: List<AbsItem> = emptyList(), val total: Int = 0, val limit: Int = 0, val page: Int = 0)

@Serializable
data class AbsItem(
    val id: String,
    val libraryId: String = "",
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val mediaType: String = "book",
    val media: AbsMedia = AbsMedia(),
)

@Serializable
data class AbsMedia(
    val metadata: AbsMetadata = AbsMetadata(),
    val coverPath: String? = null,
    val ebookFormat: String? = null,
    val ebookFile: AbsEbookFile? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class AbsEbookFile(val ino: String? = null, val ebookFormat: String? = null, val metadata: AbsFileMeta? = null)

@Serializable
data class AbsFileMeta(val filename: String? = null, val ext: String? = null, val size: Long = 0)

@Serializable
data class AbsMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val authorName: String? = null,
    val authors: List<AbsNamed> = emptyList(),
    val narratorName: String? = null,
    val seriesName: String? = null,
    /** List of {id,name,sequence} in expanded form; a single object when filtered by series. */
    val series: JsonElement? = null,
    val genres: List<String> = emptyList(),
    val publishedYear: String? = null,
    val publisher: String? = null,
    val description: String? = null,
    val isbn: String? = null,
    val language: String? = null,
)

@Serializable
data class AbsNamed(val id: String = "", val name: String = "")

@Serializable
data class AbsRefreshResponse(val user: AbsUser)

// ---------- App domain models ----------

@Serializable
data class SeriesRef(val id: String? = null, val name: String, val sequence: String? = null) {
    val sequenceNumber: Double? get() = sequence?.trim()?.toDoubleOrNull()
}

enum class BookFormat(val ext: String, val label: String) {
    EPUB("epub", "EPUB"), PDF("pdf", "PDF"), CBZ("cbz", "CBZ"), CBR("cbr", "CBR"),
    MOBI("mobi", "MOBI"), AZW3("azw3", "AZW3"), UNKNOWN("", "Ebook");

    val isComic get() = this == CBZ || this == CBR
    val isReflow get() = this == EPUB || this == MOBI || this == AZW3

    companion object {
        fun from(s: String?): BookFormat {
            val k = s?.lowercase()?.trim()?.removePrefix(".") ?: return UNKNOWN
            return when (k) {
                "azw", "azw3", "kf8" -> AZW3
                "prc" -> MOBI
                else -> entries.firstOrNull { it.ext == k } ?: UNKNOWN
            }
        }
    }
}

@Serializable
data class Book(
    val id: String,
    val libraryId: String,
    val title: String,
    val subtitle: String? = null,
    val author: String = "",
    val series: List<SeriesRef> = emptyList(),
    val format: BookFormat = BookFormat.UNKNOWN,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val publishedYear: String? = null,
    val publisher: String? = null,
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val fileSize: Long = 0,
    val fileName: String? = null,
    val tags: List<String> = emptyList(),
) {
    val primarySeries get() = series.firstOrNull()
    val sortTitle: String get() = title.lowercase().let { t ->
        listOf("the ", "a ", "an ").firstOrNull { t.startsWith(it) }?.let { t.removePrefix(it) } ?: t
    }
    val authorSort: String get() = author.split(",").first().trim().split(" ").lastOrNull()?.lowercase() ?: ""
}

private val seqRegex = Regex("""^(.*?)\s+#\s*([\d.]+)\s*$""")

/** "Name #2, Other #1" -> refs */
fun parseSeriesName(s: String?): List<SeriesRef> {
    if (s.isNullOrBlank()) return emptyList()
    return s.split(", ").mapNotNull { part ->
        val p = part.trim()
        if (p.isEmpty()) null else {
            val m = seqRegex.find(p)
            if (m != null) SeriesRef(name = m.groupValues[1].trim(), sequence = m.groupValues[2])
            else SeriesRef(name = p)
        }
    }
}

private fun JsonElement.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

fun AbsItem.toBook(): Book {
    val md = media.metadata
    val seriesFromJson: List<SeriesRef> = when (val s = md.series) {
        is JsonArray -> s.mapNotNull { e -> e.str("name")?.let { SeriesRef(e.str("id"), it, e.str("sequence")) } }
        is JsonObject -> s.str("name")?.let { listOf(SeriesRef(s.str("id"), it, s.str("sequence"))) } ?: emptyList()
        else -> emptyList()
    }
    val series = seriesFromJson.ifEmpty { parseSeriesName(md.seriesName) }
    val fmt = BookFormat.from(media.ebookFormat ?: media.ebookFile?.ebookFormat ?: media.ebookFile?.metadata?.ext)
    return Book(
        id = id,
        libraryId = libraryId,
        title = md.title?.takeIf { it.isNotBlank() } ?: "Untitled",
        subtitle = md.subtitle?.takeIf { it.isNotBlank() },
        author = md.authorName?.takeIf { it.isNotBlank() } ?: md.authors.joinToString(", ") { it.name },
        series = series,
        format = fmt,
        addedAt = addedAt,
        updatedAt = updatedAt,
        publishedYear = md.publishedYear,
        publisher = md.publisher,
        genres = md.genres,
        description = md.description,
        language = md.language,
        isbn = md.isbn,
        fileSize = media.ebookFile?.metadata?.size ?: 0,
        fileName = media.ebookFile?.metadata?.filename,
        tags = media.tags,
    )
}

/** Merges a detailed (expanded) record over a cached minified one without losing fields. */
fun Book.mergedWith(detail: Book): Book = detail.copy(
    series = detail.series.ifEmpty { series },
    format = if (detail.format == BookFormat.UNKNOWN) format else detail.format,
    description = detail.description ?: description,
    genres = detail.genres.ifEmpty { genres },
)

@Serializable
data class ReadingProgress(
    val itemId: String,
    /** 0..1 across the whole book */
    val fraction: Double = 0.0,
    /** App location string, e.g. "bs1:c:4:0.3521" or "bs1:p:41" */
    val location: String? = null,
    val finished: Boolean = false,
    val updatedAt: Long = 0,
    /** true until the server has accepted this value */
    val dirty: Boolean = false,
)

@Serializable
data class Highlight(
    val id: String,
    val chapter: Int,
    /** Serialized DOM range from the reader script, or "p:<page>" for page formats */
    val range: String,
    val text: String,
    val note: String? = null,
    val color: String = "gold",
    val createdAt: Long = System.currentTimeMillis(),
    val chapterTitle: String? = null,
)

@Serializable
data class Bookmark(
    val id: String,
    val location: String,
    val label: String,
    val fraction: Double,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Annotations(
    val highlights: List<Highlight> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
)
