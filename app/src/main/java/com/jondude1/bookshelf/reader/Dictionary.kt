package com.jondude1.bookshelf.reader

import androidx.core.text.HtmlCompat
import com.jondude1.bookshelf.data.JsonFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Sense(val partOfSpeech: String, val definitions: List<String>)

sealed interface Definition {
    val word: String
    data class Loading(override val word: String) : Definition
    data class Found(override val word: String, val senses: List<Sense>) : Definition
    data class Missing(override val word: String, val reason: String) : Definition
}

@Serializable private data class WkDef(val definition: String = "")
@Serializable private data class WkEntry(val partOfSpeech: String = "", val language: String = "", val definitions: List<WkDef> = emptyList())

/** English definitions from Wiktionary, cached for the session. */
object Dictionary {
    private val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val cache = HashMap<String, Definition>()

    fun normalize(selection: String): String =
        selection.trim().split(Regex("\\s+")).first().trim { !it.isLetterOrDigit() && it != '-' && it != '\'' }.removeSuffix("'s").removeSuffix("’s")

    suspend fun lookup(raw: String): Definition = withContext(Dispatchers.IO) {
        val word = normalize(raw)
        if (word.isBlank()) return@withContext Definition.Missing(raw, "Nothing to look up")
        cache[word.lowercase()]?.let { return@withContext it }
        val result = fetch(word) ?: if (word.any { it.isUpperCase() }) fetch(word.lowercase()) else null
        val def = result?.let { Definition.Found(word, it) } ?: Definition.Missing(word, "No definition found")
        cache[word.lowercase()] = def
        def
    }

    private fun fetch(word: String): List<Sense>? = runCatching {
        val url = "https://en.wiktionary.org/api/rest_v1/page/definition/" + URLEncoder.encode(word, "UTF-8").replace("+", "%20")
        val req = Request.Builder().url(url).header("User-Agent", "BookShelf/1.0 (Android ebook reader)").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@runCatching null
            val map = JsonFmt.decodeFromString(MapSerializer(String.serializer(), ListSerializer(WkEntry.serializer())), r.body!!.string())
            val en = map["en"].orEmpty()
            en.map { e ->
                Sense(e.partOfSpeech, e.definitions.map { clean(it.definition) }.filter { it.isNotBlank() }.take(4))
            }.filter { it.definitions.isNotEmpty() }.take(4).ifEmpty { null }
        }
    }.getOrNull()

    private fun clean(html: String) = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\\s+"), " ").trim()
}
