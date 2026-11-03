package com.jondude1.bookshelf.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.LocalDate

/** Small atomic JSON file. */
class JsonFile<T>(private val file: File, private val ser: KSerializer<T>, private val default: () -> T) {
    fun load(): T = runCatching { if (file.exists()) JsonFmt.decodeFromString(ser, file.readText()) else default() }
        .getOrElse { default() }

    fun save(v: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JsonFmt.encodeToString(ser, v))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }
}

sealed interface LoadState {
    data object Idle : LoadState
    data class Loading(val done: Int, val total: Int) : LoadState
    data class Error(val message: String) : LoadState
}

sealed interface DownloadState {
    data class Running(val progress: Float) : DownloadState
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}

// ===================== Library =====================

class LibraryRepository(context: Context, private val client: AbsClient, private val prefs: Prefs, private val scope: CoroutineScope) {
    private val store = JsonFile(File(context.filesDir, "library.json"), ListSerializer(Book.serializer())) { emptyList() }
    private val _books = MutableStateFlow(store.load().distinctBy { it.id })
    val books: StateFlow<List<Book>> = _books
    private val _state = MutableStateFlow<LoadState>(LoadState.Idle)
    val state: StateFlow<LoadState> = _state
    private val collectionStore = JsonFile(File(context.filesDir, "collections.json"), ListSerializer(BookCollection.serializer())) { emptyList() }
    private val _collections = MutableStateFlow(collectionStore.load())
    /** Audiobookshelf collections that contain at least one ebook. */
    val collections: StateFlow<List<BookCollection>> = _collections
    private val lock = Mutex()
    private var lastRefresh = 0L

    fun book(id: String): Book? = _books.value.firstOrNull { it.id == id }

    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastRefresh > 5 * 60_000) scope.launch { refresh() }
    }

    suspend fun refresh(): Result<Unit> = lock.withLock {
        runCatching {
            _state.value = LoadState.Loading(0, 0)
            var libId = prefs.server.value.libraryId
            val libs = client.libraries().filter { it.mediaType == "book" }
            if (libs.isEmpty()) throw AbsException("No book libraries on this Audiobookshelf server")
            val ids = if (libId.isNotBlank() && libs.any { it.id == libId }) listOf(libId) else libs.map { it.id }
            if (libId.isBlank() || libs.none { it.id == libId }) libId = ""
            val items = ids.flatMap { id -> client.allEbookItems(id) { d, t -> _state.value = LoadState.Loading(d, t) } }.distinctBy { it.id }
            val old = _books.value.associateBy { it.id }
            val merged = items.map { it.toBook() }.map { b -> old[b.id]?.let { o -> o.mergedWith(b).copy(description = b.description ?: o.description) } ?: b }
            _books.value = merged
            withContext(Dispatchers.IO) { store.save(merged) }
            lastRefresh = System.currentTimeMillis()
            // Collections are a nice-to-have; a failure here never fails the refresh
            runCatching {
                val known = merged.map { it.id }.toSet()
                val cols = ids.flatMap { client.collections(it) }.map { c ->
                    BookCollection(c.id, c.name, c.description?.takeIf { it.isNotBlank() }, c.books.map { it.id }.filter { it in known })
                }.filter { it.bookIds.isNotEmpty() }.distinctBy { it.id }
                _collections.value = cols
                withContext(Dispatchers.IO) { collectionStore.save(cols) }
            }
            _state.value = LoadState.Idle
        }.onFailure { _state.value = LoadState.Error(it.message ?: "Couldn't reach Athena") }
    }

    /** Pulls the full record for one book (series ids, description, file info). */
    suspend fun detail(id: String): Result<Book> = runCatching {
        val fresh = client.item(id).toBook()
        val merged = book(id)?.mergedWith(fresh) ?: fresh
        _books.update { list -> if (list.any { it.id == id }) list.map { if (it.id == id) merged else it } else list + merged }
        withContext(Dispatchers.IO) { store.save(_books.value) }
        merged
    }

    fun seriesBooks(name: String): List<Book> = _books.value
        .distinctBy { it.id }
        .filter { b -> b.series.any { it.name.equals(name, true) } }
        .sortedWith(compareBy<Book> { b -> b.series.first { it.name.equals(name, true) }.sequenceNumber ?: Double.MAX_VALUE }.thenBy { it.sortTitle })

    fun nextInSeries(book: Book): Book? {
        val s = book.primarySeries ?: return null
        val list = seriesBooks(s.name)
        val i = list.indexOfFirst { it.id == book.id }
        return if (i >= 0) list.getOrNull(i + 1) else null
    }
}

// ===================== Progress =====================

class ProgressRepository(context: Context, private val client: AbsClient, private val scope: CoroutineScope) {
    private val store = JsonFile(File(context.filesDir, "progress.json"),
        MapSerializer(String.serializer(), ReadingProgress.serializer())) { emptyMap() }
    private val _all = MutableStateFlow(store.load())
    val all: StateFlow<Map<String, ReadingProgress>> = _all
    private var pushJob: Job? = null

    fun get(id: String) = _all.value[id]

    private fun persist() = scope.launch(Dispatchers.IO) { store.save(_all.value) }

    /** Called by readers on every page turn. Pushes to Athena a few seconds later. */
    fun update(id: String, fraction: Double, location: String?, finished: Boolean = false) {
        val p = ReadingProgress(id, fraction.coerceIn(0.0, 1.0), location,
            finished || fraction >= 0.995, System.currentTimeMillis(), dirty = true)
        _all.update { it + (id to p) }
        persist()
        pushJob?.cancel()
        pushJob = scope.launch { delay(4000); pushDirty() }
    }

    fun markFinished(id: String, finished: Boolean) {
        val cur = get(id) ?: ReadingProgress(id)
        _all.update { it + (id to cur.copy(finished = finished, fraction = if (finished) 1.0 else cur.fraction.coerceAtMost(0.99),
            updatedAt = System.currentTimeMillis(), dirty = true)) }
        persist()
        scope.launch { pushDirty() }
    }

    fun flush() { scope.launch { pushDirty() } }

    suspend fun pushDirty() {
        val dirty = _all.value.values.filter { it.dirty }
        for (p in dirty) {
            val ok = runCatching { client.pushProgress(p) }.isSuccess
            if (ok) _all.update { m -> m[p.itemId]?.let { cur -> if (cur.updatedAt == p.updatedAt) m + (p.itemId to cur.copy(dirty = false)) else m } ?: m }
            else break
        }
        persist()
    }

    /** Merges server progress for all books; newer side wins. */
    suspend fun syncFromServer() {
        val server = runCatching { client.me().mediaProgress }.getOrNull() ?: return
        _all.update { local ->
            val out = local.toMutableMap()
            for (sp in server) {
                if (sp.episodeId != null) continue
                if (sp.ebookProgress == null && sp.ebookLocation == null && !sp.isFinished) continue
                val l = local[sp.libraryItemId]
                if (l == null || (!l.dirty && sp.lastUpdate > l.updatedAt)) {
                    out[sp.libraryItemId] = ReadingProgress(sp.libraryItemId, sp.ebookProgress ?: if (sp.isFinished) 1.0 else 0.0,
                        sp.ebookLocation?.takeIf { it.startsWith("bs1:") }, sp.isFinished, sp.lastUpdate, false)
                }
            }
            out
        }
        persist()
        pushDirty()
    }

    /** Freshest position for a book just before opening it. */
    suspend fun resolveForOpen(id: String): ReadingProgress? {
        val local = get(id)
        val sp = runCatching { client.progress(id) }.getOrNull() ?: return local
        if (local == null || (sp.lastUpdate > local.updatedAt && !local.dirty)) {
            val p = ReadingProgress(id, sp.ebookProgress ?: 0.0, sp.ebookLocation?.takeIf { it.startsWith("bs1:") }, sp.isFinished, sp.lastUpdate)
            _all.update { it + (id to p) }
            persist()
            return p
        }
        return local
    }
}

// ===================== Files / offline =====================

class FileRepository(private val context: Context, private val client: AbsClient, private val scope: CoroutineScope) {
    private val offlineDir = File(context.filesDir, "books").apply { mkdirs() }
    private val cacheDir = File(context.cacheDir, "books").apply { mkdirs() }
    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads
    private val _offline = MutableStateFlow(scanOffline())
    val offline: StateFlow<Set<String>> = _offline

    private fun scanOffline() = offlineDir.listFiles().orEmpty().filter { !it.name.endsWith(".part") }
        .map { it.nameWithoutExtension }.toSet()

    private fun name(book: Book) = book.id + "." + (book.format.ext.ifBlank { book.fileName?.substringAfterLast('.', "bin") ?: "bin" })

    fun localFile(book: Book): File? =
        listOf(File(offlineDir, name(book)), File(cacheDir, name(book))).firstOrNull { it.exists() && it.length() > 0 }

    fun offlineSize(): Long = offlineDir.listFiles().orEmpty().sumOf { it.length() }
    fun offlineFile(id: String): File? = offlineDir.listFiles().orEmpty().firstOrNull { it.nameWithoutExtension == id }

    /** File to open now: offline copy, cached copy, or a fresh download into the cache. */
    suspend fun open(book: Book, onProgress: (Float) -> Unit): File {
        localFile(book)?.let { it.setLastModified(System.currentTimeMillis()); return it }
        val f = File(cacheDir, name(book))
        client.downloadEbook(book.id, f, onProgress)
        trimCache(keep = f)
        return f
    }

    /** Keeps a permanent offline copy. */
    fun download(book: Book) {
        if (_downloads.value[book.id] is DownloadState.Running) return
        scope.launch {
            _downloads.update { it + (book.id to DownloadState.Running(0f)) }
            runCatching {
                val dest = File(offlineDir, name(book))
                val cached = File(cacheDir, name(book))
                if (cached.exists() && cached.length() > 0) {
                    withContext(Dispatchers.IO) { cached.copyTo(dest, overwrite = true); cached.delete() }
                } else {
                    client.downloadEbook(book.id, dest) { p -> _downloads.update { it + (book.id to DownloadState.Running(p)) } }
                }
            }.onSuccess {
                _downloads.update { it + (book.id to DownloadState.Done) }
                _offline.value = scanOffline()
            }.onFailure { e ->
                _downloads.update { it + (book.id to DownloadState.Failed(e.message ?: "Download failed")) }
            }
        }
    }

    fun remove(id: String) {
        offlineDir.listFiles().orEmpty().filter { it.nameWithoutExtension == id }.forEach { it.delete() }
        _downloads.update { it - id }
        _offline.value = scanOffline()
    }

    private fun trimCache(keep: File, limit: Long = 1_500L * 1024 * 1024) {
        val files = cacheDir.listFiles().orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= limit) break
            if (f == keep) continue
            total -= f.length(); f.delete()
        }
    }

    fun clearCache() { cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() } }
    fun cacheSize(): Long = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

// ===================== Annotations =====================

class AnnotationRepository(context: Context) {
    private val dir = File(context.filesDir, "annotations").apply { mkdirs() }
    private fun file(id: String) = JsonFile(File(dir, "$id.json"), Annotations.serializer()) { Annotations() }
    private val cache = mutableMapOf<String, MutableStateFlow<Annotations>>()

    fun flow(id: String): StateFlow<Annotations> = cache.getOrPut(id) { MutableStateFlow(file(id).load()) }

    fun edit(id: String, f: (Annotations) -> Annotations) {
        val s = flow(id) as MutableStateFlow
        s.value = f(s.value)
        file(id).save(s.value)
    }

    fun allBooksWithNotes(): List<String> = dir.listFiles().orEmpty().map { it.nameWithoutExtension }

    /** Markdown export of every highlight and note for one book. */
    fun exportMarkdown(book: Book): String {
        val a = flow(book.id).value
        return buildString {
            append("# ").append(book.title).append('\n')
            if (book.author.isNotBlank()) append("*").append(book.author).append("*\n")
            append('\n')
            var lastChapter: String? = null
            for (h in a.highlights.sortedWith(compareBy({ it.chapter }, { it.createdAt }))) {
                val ch = h.chapterTitle
                if (ch != null && ch != lastChapter) { append("\n## ").append(ch).append("\n\n"); lastChapter = ch }
                append("> ").append(h.text.replace("\n", "\n> ")).append("\n\n")
                h.note?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
            }
            if (a.bookmarks.isNotEmpty()) {
                append("\n## Bookmarks\n\n")
                a.bookmarks.forEach { append("- ").append(it.label).append(" (").append((it.fraction * 100).toInt()).append("%)\n") }
            }
        }
    }
}

// ===================== Reading stats =====================

@Serializable
data class DayStat(val seconds: Long = 0, val pages: Int = 0)

@Serializable
data class StatsData(
    val days: Map<String, DayStat> = emptyMap(),
    val finished: Map<String, String> = emptyMap(),
    /** Rolling average seconds per page, per book format family */
    val secondsPerPage: Double = 45.0,
)

class StatsRepository(context: Context, private val scope: CoroutineScope) {
    private val store = JsonFile(File(context.filesDir, "stats.json"), StatsData.serializer()) { StatsData() }
    private val _data = MutableStateFlow(store.load())
    val data: StateFlow<StatsData> = _data

    fun record(seconds: Long, pages: Int) {
        if (seconds <= 0 && pages <= 0) return
        val day = LocalDate.now().toString()
        _data.update { d ->
            val cur = d.days[day] ?: DayStat()
            val spp = if (pages > 0 && seconds > 0) {
                val sample = (seconds.toDouble() / pages).coerceIn(5.0, 600.0)
                d.secondsPerPage * 0.85 + sample * 0.15
            } else d.secondsPerPage
            d.copy(days = d.days + (day to DayStat(cur.seconds + seconds, cur.pages + pages)), secondsPerPage = spp)
        }
        scope.launch(Dispatchers.IO) { store.save(_data.value) }
    }

    fun markFinished(id: String) {
        if (_data.value.finished.containsKey(id)) return
        _data.update { it.copy(finished = it.finished + (id to LocalDate.now().toString())) }
        scope.launch(Dispatchers.IO) { store.save(_data.value) }
    }

    fun streak(goalSeconds: Long = 60): Int {
        val days = _data.value.days
        var d = LocalDate.now()
        if ((days[d.toString()]?.seconds ?: 0) < goalSeconds) d = d.minusDays(1)
        var n = 0
        while ((days[d.toString()]?.seconds ?: 0) >= goalSeconds) { n++; d = d.minusDays(1) }
        return n
    }
}
