package com.jondude1.bookshelf.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

private val Context.dataStore by preferencesDataStore("bookshelf")

@Serializable
data class ServerConfig(
    /** Address used at home, e.g. http://192.168.1.20:13378 */
    val lanUrl: String = "",
    /** Address used away from home, e.g. http://athena:13378 over Tailscale */
    val remoteUrl: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val usingApiKey: Boolean = false,
    val username: String = "",
    val userId: String = "",
    val libraryId: String = "",
) {
    val isLoggedIn get() = accessToken.isNotBlank() && (lanUrl.isNotBlank() || remoteUrl.isNotBlank())
    val urls get() = listOf(lanUrl, remoteUrl).map { it.trim().trimEnd('/') }.filter { it.isNotBlank() }.distinct()
}

enum class ReaderTheme(val label: String, val bg: Long, val fg: Long, val dark: Boolean) {
    PAPER("Paper", 0xFFFBF8F1, 0xFF1E1C19, false),
    SEPIA("Sepia", 0xFFF3E9D2, 0xFF4E3B28, false),
    SAGE("Sage", 0xFFE6EBDF, 0xFF2B3326, false),
    SLATE("Slate", 0xFF26282D, 0xFFD9D6CF, true),
    NIGHT("Night", 0xFF141414, 0xFFC8C3B8, true),
    OLED("Black", 0xFF000000, 0xFFAFA99D, true),
}

enum class ReaderFont(val label: String, val css: String) {
    ORIGINAL("Book default", ""),
    LITERATA("Literata", "'BS Literata', serif"),
    SERIF("Noto Serif", "serif"),
    SANS("Sans", "'BS Inter', sans-serif"),
    ATKINSON("Hyperlegible", "'BS Atkinson', sans-serif"),
    MONO("Mono", "monospace"),
}

enum class SpreadMode(val label: String) { AUTO("Auto"), SINGLE("Single"), DOUBLE("Two pages") }

@Serializable
data class ReaderPrefs(
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val autoNight: Boolean = true,
    val nightTheme: ReaderTheme = ReaderTheme.NIGHT,
    val nightStartHour: Int = 21,
    val nightEndHour: Int = 7,
    val font: ReaderFont = ReaderFont.LITERATA,
    val fontScale: Float = 1.0f,
    val lineHeight: Float = 1.55f,
    val margin: Int = 1,
    val justify: Boolean = true,
    val hyphenate: Boolean = true,
    val publisherStyles: Boolean = false,
    val spread: SpreadMode = SpreadMode.AUTO,
    val tapZones: Boolean = true,
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = true,
    val warmth: Float = 0f,
    val brightness: Float = -1f,
    val comicRightToLeft: Boolean = false,
    val invertPdfAtNight: Boolean = false,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val autoDownloadNext: Boolean = true,
    val dailyGoalMinutes: Int = 30,
    val showClock: Boolean = true,
    /** SLIDE, FADE or NONE */
    val pageTurn: String = "SLIDE",
    // Library view, remembered between launches
    val librarySort: String = "TITLE",
    val libraryShelf: String = "ALL",
    val libraryFormat: String? = null,
    val libraryGrid: Boolean = true,
) {
    /** The theme to use right now, honoring the automatic night window. */
    fun effectiveTheme(hour: Int): ReaderTheme {
        if (!autoNight) return theme
        val night = if (nightStartHour > nightEndHour) hour >= nightStartHour || hour < nightEndHour
        else hour in nightStartHour until nightEndHour
        return if (night && !theme.dark) nightTheme else theme
    }
}

class Prefs(private val context: Context, private val scope: CoroutineScope) {
    private val serverKey = stringPreferencesKey("server")
    private val readerKey = stringPreferencesKey("reader")

    private val _server = MutableStateFlow(ServerConfig())
    val server: StateFlow<ServerConfig> = _server
    private val _reader = MutableStateFlow(ReaderPrefs())
    val reader: StateFlow<ReaderPrefs> = _reader

    init {
        // Loaded synchronously once so the first screen knows whether we're logged in.
        runBlocking {
            val p = context.dataStore.data.first()
            p[serverKey]?.let { runCatching { _server.value = JsonFmt.decodeFromString(ServerConfig.serializer(), it) } }
            p[readerKey]?.let { runCatching { _reader.value = JsonFmt.decodeFromString(ReaderPrefs.serializer(), it) } }
        }
    }

    fun updateServer(f: (ServerConfig) -> ServerConfig) {
        val v = f(_server.value)
        _server.value = v
        scope.launch { context.dataStore.edit { it[serverKey] = JsonFmt.encodeToString(ServerConfig.serializer(), v) } }
    }

    fun updateReader(f: (ReaderPrefs) -> ReaderPrefs) {
        val v = f(_reader.value)
        _reader.value = v
        scope.launch { context.dataStore.edit { it[readerKey] = JsonFmt.encodeToString(ReaderPrefs.serializer(), v) } }
    }
}
