package com.jondude1.bookshelf.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jondude1.bookshelf.Graph
import com.jondude1.bookshelf.data.Book
import com.jondude1.bookshelf.data.Bookmark
import com.jondude1.bookshelf.data.Highlight
import com.jondude1.bookshelf.data.JsonFmt
import com.jondude1.bookshelf.data.ReaderPrefs
import com.jondude1.bookshelf.data.ReadingProgress
import com.jondude1.bookshelf.data.SpreadMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
private data class SelectionJson(val range: String, val text: String)

data class ReturnPoint(val chapter: Int, val inChapter: Double, val label: String)

data class SearchHit(val chapter: Int, val nth: Int, val snippet: String, val chapterTitle: String?)

/** Owns the WebView and everything that happens while reading a reflowable book. */
class ReflowController(
    val book: ReflowBook,
    val meta: Book,
    private val context: Context,
    private val scope: CoroutineScope,
    val session: ReadingSession,
) : ReaderBridge {
    val web = BookWebView(context, this).apply {
        book = this@ReflowController.book
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        onLoaded = { onPageLoaded() }
    }
    val tts = TtsReader(context)

    var chapter by mutableIntStateOf(0); private set
    var page by mutableIntStateOf(0); private set
    var pageCount by mutableIntStateOf(1); private set
    var ready by mutableStateOf(false); private set
    var selectedHighlight by mutableStateOf<Highlight?>(null)
    var noteDraft by mutableStateOf<Highlight?>(null)
    /** Where the reader was before the last jump (contents, link, search, slider…). */
    var returnPoint by mutableStateOf<ReturnPoint?>(null); private set
    private var turnsSinceJump = 0

    private fun markJump() {
        if (!ready) return
        returnPoint = ReturnPoint(chapter, inChapter, "p. ${page + 1}" + (chapterTitle?.let { " · $it" } ?: ""))
        turnsSinceJump = 0
    }

    fun goBack() {
        val r = returnPoint ?: return
        returnPoint = null
        if (r.chapter == chapter && ready) web.evaluateJavascript("BS.goFraction(${r.inChapter})", null)
        else load(r.chapter, fractionSpec(r.inChapter))
    }

    fun dismissReturn() { returnPoint = null }

    /** Footnote shown in a pop-up: text and the link to its full location. */
    var footnote by mutableStateOf<Pair<String, String>?>(null)

    override fun onFootnote(text: String, url: String) { footnote = text to url }

    private var startSpec: String = """{"type":"first"}"""
    private var configJson: String = "{}"
    private var ttsContinue = false
    private val annotations get() = Graph.annotations.flow(meta.id).value

    val chapterTitle get() = book.chapterTitle(chapter)
    val inChapter: Double get() = if (pageCount <= 0) 0.0 else page.toDouble() / pageCount
    val fraction: Double get() = book.bookFraction(chapter, if (pageCount <= 1) 0.0 else page.toDouble() / pageCount)
    val location get() = "bs1:c:$chapter:${"%.5f".format(java.util.Locale.US, inChapter)}"

    fun open(progress: ReadingProgress?) {
        val loc = progress?.location
        val parts = loc?.split(':')
        if (parts != null && parts.size == 4 && parts[0] == "bs1" && parts[1] == "c") {
            val c = parts[2].toIntOrNull()?.coerceIn(0, book.chapters.lastIndex) ?: 0
            load(c, fractionSpec(parts[3].toDoubleOrNull() ?: 0.0))
        } else if (progress != null && progress.fraction > 0 && !progress.finished) {
            val (c, f) = book.positionFor(progress.fraction)
            load(c, fractionSpec(f))
        } else load(0, """{"type":"first"}""")
    }

    private fun fractionSpec(f: Double) = buildJsonObject { put("type", "fraction"); put("value", f) }.toString()

    /** +1 when moving forward a chapter, -1 backward, 0 for jumps (fade only). */
    private var slideDir = 0

    fun load(index: Int, spec: String, direction: Int = 0) {
        slideDir = direction
        if (index !in book.chapters.indices) return
        ready = false
        chapter = index
        startSpec = spec
        web.animate().cancel()
        web.alpha = 0f
        web.loadUrl(web.chapterUrl(book.chapters[index].path))
    }

    fun configure(prefs: ReaderPrefs, colors: ReaderColors, topDp: Int, bottomDp: Int) {
        val spread = when (prefs.spread) { SpreadMode.SINGLE -> false; else -> true }
        configJson = buildJsonObject {
            put("bg", cssColor(colors.bg)); put("fg", cssColor(colors.fg)); put("accent", cssColor(colors.accent))
            put("font", prefs.font.css); put("scale", prefs.fontScale); put("lineHeight", prefs.lineHeight)
            put("margin", prefs.margin.coerceIn(0, 2)); put("justify", prefs.justify); put("hyphenate", prefs.hyphenate)
            put("publisher", prefs.publisherStyles); put("spread", spread); put("tapZones", prefs.tapZones)
            put("top", topDp); put("bottom", bottomDp); put("dropStyles", false)
        }.toString()
        web.setBackgroundColor(android.graphics.Color.argb(255, (colors.bg.red * 255).toInt(), (colors.bg.green * 255).toInt(), (colors.bg.blue * 255).toInt()))
        if (ready) web.evaluateJavascript("BS.configure($configJson)", null)
    }

    private fun highlightsJson(): String = buildJsonArray {
        annotations.highlights.filter { it.chapter == chapter }.forEach { h ->
            add(buildJsonObject { put("id", h.id); put("range", h.range); put("color", h.color); put("note", JsonPrimitive(h.note)) })
        }
    }.toString()

    private fun onPageLoaded() {
        web.evaluateJavascript("window.BS && BS.init($configJson, $startSpec, ${highlightsJson()})", null)
    }

    // ---------- bridge ----------
    override fun onReady(page: Int, count: Int) {
        this.page = page; pageCount = count; ready = true
        web.translationX = slideDir * web.width * 0.18f
        web.animate().alpha(1f).translationX(0f).setDuration(if (slideDir == 0) 160 else 240)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        saveProgress(turned = false)
        if (ttsContinue) { ttsContinue = false; startTts(fromStart = true) }
    }

    override fun onPage(page: Int, count: Int) {
        val turned = page != this.page
        if (turned && returnPoint != null && ++turnsSinceJump > 6) returnPoint = null
        this.page = page; pageCount = count
        if (ready) saveProgress(turned)
    }

    private fun saveProgress(turned: Boolean) {
        Graph.progress.update(meta.id, fraction, location)
        if (turned) session.pageTurned()
        session.chapterPagesLeft = (pageCount - page - 1).coerceAtLeast(0)
        val chSize = book.chapters[chapter].size.coerceAtLeast(1).toDouble()
        val total = book.chapters.sumOf { it.size.coerceAtLeast(1) }.toDouble()
        session.bookPagesLeft = ((pageCount * total / chSize) * (1 - fraction)).toInt()
    }

    override fun onEdge(direction: Int) {
        if (direction > 0) {
            if (chapter < book.chapters.lastIndex) load(chapter + 1, """{"type":"first"}""", 1) else session.reachedEnd()
        } else if (chapter > 0) load(chapter - 1, """{"type":"last"}""", -1)
    }

    override fun onTap() { session.toggleChrome() }

    override fun onLink(url: String) {
        val uri = Uri.parse(url)
        if (uri.host == BookWebView.HOST && uri.path?.startsWith("/b/") == true) {
            val path = uri.path!!.removePrefix("/b/")
            val frag = uri.fragment
            val idx = book.chapterIndexOf(path)
            if (idx < 0) return
            markJump()
            if (idx == chapter && frag != null) web.evaluateJavascript("BS.goFragment(${JsonPrimitive(frag)})", null)
            else load(idx, if (frag != null) buildJsonObject { put("type", "fragment"); put("value", frag) }.toString() else """{"type":"first"}""")
        } else if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    override fun onHighlightTap(id: String) { selectedHighlight = annotations.highlights.firstOrNull { it.id == id } }

    /** Illustration opened in the full-screen viewer. */
    var image by mutableStateOf<android.graphics.Bitmap?>(null)

    override fun onImage(url: String) {
        val path = Uri.parse(url).path?.removePrefix("/b/") ?: return
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { book.resource(path)?.bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
            }
            if (bmp != null) image = bmp else session.toggleChrome()
        }
    }

    override fun onSelection(action: SelectionAction, json: String) {
        val sel = runCatching { JsonFmt.decodeFromString(SelectionJson.serializer(), json) }.getOrNull() ?: return
        val text = sel.text.trim()
        when (action) {
            SelectionAction.HIGHLIGHT -> addHighlight(sel.range, text, null)
            SelectionAction.NOTE -> noteDraft = Highlight(UUID.randomUUID().toString(), chapter, sel.range, text, chapterTitle = chapterTitle)
            SelectionAction.COPY -> copy(text)
            SelectionAction.DEFINE -> define(text)
            SelectionAction.SHARE -> share("“$text”\n— ${meta.title}${if (meta.author.isNotBlank()) ", ${meta.author}" else ""}")
        }
    }

    // ---------- annotations ----------
    fun addHighlight(range: String, text: String, note: String?, color: String = "gold", id: String = UUID.randomUUID().toString()) {
        val h = Highlight(id, chapter, range, text, note?.takeIf { it.isNotBlank() }, color, chapterTitle = chapterTitle)
        Graph.annotations.edit(meta.id) { a -> a.copy(highlights = a.highlights.filter { it.id != id } + h) }
        refreshHighlights()
    }

    fun updateHighlight(h: Highlight) {
        Graph.annotations.edit(meta.id) { a -> a.copy(highlights = a.highlights.map { if (it.id == h.id) h else it }) }
        refreshHighlights()
    }

    fun deleteHighlight(id: String) {
        Graph.annotations.edit(meta.id) { a -> a.copy(highlights = a.highlights.filter { it.id != id }) }
        web.evaluateJavascript("BS.removeHighlight(${JsonPrimitive(id)})", null)
    }

    private fun refreshHighlights() = web.evaluateJavascript("BS.setHighlights(${highlightsJson()})", null)

    fun goToHighlight(h: Highlight) {
        markJump()
        val start = runCatching { Regex(""""s"\s*:\s*(\d+)""").find(h.range)?.groupValues?.get(1)?.toInt() }.getOrNull() ?: 0
        if (h.chapter == chapter && ready) web.evaluateJavascript("BS.goOffset($start)", null)
        else load(h.chapter, buildJsonObject { put("type", "offset"); put("value", start) }.toString())
    }

    fun currentBookmark(): Bookmark? {
        val tolerance = 1.0 / pageCount.coerceAtLeast(1)
        return annotations.bookmarks.firstOrNull { b ->
            val p = b.location.split(':')
            p.size == 4 && p[2].toIntOrNull() == chapter && kotlin.math.abs((p[3].toDoubleOrNull() ?: -1.0) - inChapter) < tolerance * 0.75
        }
    }

    fun toggleBookmark() {
        val existing = currentBookmark()
        Graph.annotations.edit(meta.id) { a ->
            if (existing != null) a.copy(bookmarks = a.bookmarks - existing)
            else a.copy(bookmarks = a.bookmarks + Bookmark(UUID.randomUUID().toString(), location,
                "${chapterTitle ?: "Page"} · p. ${page + 1}", fraction))
        }
    }

    fun goToBookmark(b: Bookmark) {
        markJump()
        val p = b.location.split(':')
        val c = p.getOrNull(2)?.toIntOrNull() ?: return
        val f = p.getOrNull(3)?.toDoubleOrNull() ?: 0.0
        if (c == chapter && ready) web.evaluateJavascript("BS.goFraction($f)", null) else load(c, fractionSpec(f))
    }

    fun seek(f: Double) {
        markJump()
        val (c, inC) = book.positionFor(f)
        if (c == chapter && ready) web.evaluateJavascript("BS.goFraction($inC)", null) else load(c, fractionSpec(inC))
    }

    fun goToToc(e: TocEntry) {
        markJump()
        if (e.fragment != null) load(e.chapterIndex, buildJsonObject { put("type", "fragment"); put("value", e.fragment) }.toString())
        else load(e.chapterIndex, """{"type":"first"}""")
    }

    fun next() = web.evaluateJavascript("BS.next()", null)
    fun prev() = web.evaluateJavascript("BS.prev()", null)

    // ---------- search ----------
    suspend fun search(q: String): List<SearchHit> = withContext(Dispatchers.Default) {
        if (q.length < 2) return@withContext emptyList()
        val out = ArrayList<SearchHit>()
        val ql = q.lowercase()
        for (i in book.chapters.indices) {
            val text = runCatching { book.chapterText(i) }.getOrDefault("")
            val lower = text.lowercase()
            var from = 0; var n = 0
            while (out.size < 300) {
                val idx = lower.indexOf(ql, from); if (idx < 0) break
                val s = (idx - 60).coerceAtLeast(0); val e = (idx + q.length + 80).coerceAtMost(text.length)
                out += SearchHit(i, n, (if (s > 0) "…" else "") + text.substring(s, e).replace('\n', ' ') + (if (e < text.length) "…" else ""), book.chapterTitle(i))
                n++; from = idx + q.length
            }
        }
        out
    }

    fun goToHit(q: String, hit: SearchHit) {
        markJump()
        load(hit.chapter, buildJsonObject { put("type", "first"); put("search", q); put("nth", hit.nth) }.toString())
    }

    // ---------- read aloud ----------
    init {
        tts.onBlock = { i -> web.evaluateJavascript("BS.ttsMark($i)", null) }
        tts.onFinished = {
            web.evaluateJavascript("BS.ttsClear()", null)
            if (chapter < book.chapters.lastIndex) { ttsContinue = true; load(chapter + 1, """{"type":"first"}""", 1) }
        }
    }

    fun startTts(fromStart: Boolean = false) {
        web.evaluateJavascript("BS.ttsBlocks($fromStart)") { raw ->
            val json = raw?.let { BookWebView.decodeJsString(it) } ?: return@evaluateJavascript
            val blocks = runCatching { JsonFmt.decodeFromString(ListSerializer(String.serializer()), json) }.getOrDefault(emptyList())
            val p = Graph.prefs.reader.value
            if (blocks.isEmpty() && chapter < book.chapters.lastIndex) { ttsContinue = true; load(chapter + 1, """{"type":"first"}"""); return@evaluateJavascript }
            tts.speak(blocks, p.ttsRate, p.ttsPitch)
        }
    }

    fun stopTts() { ttsContinue = false; tts.stop(); web.evaluateJavascript("BS.ttsClear()", null) }

    // ---------- helpers ----------
    private fun copy(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Quote", text))
    }

    private fun define(text: String) {
        val word = text.split(Regex("\\s+")).take(4).joinToString(" ")
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, "define $word").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=define+" + Uri.encode(word))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun share(text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun dispose() {
        tts.shutdown()
        web.stopLoading()
        web.destroy()
        runCatching { book.close() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReflowReader(book: ReflowBook, meta: Book, initial: ReadingProgress?, session: ReadingSession, colors: ReaderColors, prefs: ReaderPrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { ReflowController(book, meta, context, scope, session) }
    val density = LocalDensity.current
    val cutoutTop = with(density) { WindowInsets.displayCutout.getTop(this).toDp().value.toInt() }
    val topDp = (cutoutTop + 40).coerceAtLeast(44)
    val bottomDp = 44

    DisposableEffect(Unit) {
        session.keyHandler = { forward -> if (forward) controller.next() else controller.prev() }
        onDispose { session.keyHandler = null; controller.dispose() }
    }
    LaunchedEffect(prefs, colors, topDp) { controller.configure(prefs, colors, topDp, bottomDp) }
    LaunchedEffect(Unit) { controller.configure(prefs, colors, topDp, bottomDp); controller.open(initial) }
    LaunchedEffect(prefs.keepScreenOn) { controller.web.keepScreenOn = prefs.keepScreenOn }

    session.seekHandler = { controller.seek(it.toDouble()) }

    val annotations by Graph.annotations.flow(meta.id).collectAsState()
    var sheet by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        AndroidView(factory = { controller.web }, modifier = Modifier.fillMaxSize())
        if (!controller.ready) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = colors.accent.copy(alpha = 0.6f), strokeWidth = 2.dp)

        val stats by Graph.stats.data.collectAsState()
        val minsCh = (session.chapterPagesLeft * stats.secondsPerPage / 60).toInt()
        val minsBook = (session.bookPagesLeft * stats.secondsPerPage / 60).toInt()
        if (!session.chromeVisible) ReaderFooter(
            colors,
            left = if (minsCh < 1) "Last page in chapter" else "${formatMinutes(minsCh)} left in chapter",
            right = "${(controller.fraction * 100).toInt()}%",
            top = controller.chapterTitle,
            showClock = prefs.showClock,
            topInset = topDp,
        )

        androidx.compose.animation.AnimatedVisibility(
            visible = controller.returnPoint != null,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it },
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (session.chromeVisible) 190.dp else 40.dp),
        ) {
            val rp = controller.returnPoint
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(colors.panel).clickable { controller.goBack() }
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Back to ${rp?.label ?: ""}", color = colors.fg, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    modifier = Modifier.widthIn(max = 240.dp), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                IconButton(onClick = { controller.dismissReturn() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Dismiss", tint = colors.muted, modifier = Modifier.size(16.dp))
                }
            }
        }

        ReaderChrome(
            visible = session.chromeVisible,
            colors = colors,
            title = meta.title,
            subtitle = controller.chapterTitle,
            fraction = controller.fraction.toFloat(),
            pageLabel = "Page ${controller.page + 1} of ${controller.pageCount} in chapter",
            timeLabel = if (minsBook > 0) "${formatMinutes(minsBook)} left in book" else null,
            bookmarked = annotations.bookmarks.isNotEmpty() && controller.currentBookmark() != null,
            ttsAvailable = controller.tts.ready,
            ttsPlaying = controller.tts.speaking,
            onBack = session.onBack,
            onContents = { sheet = "contents" },
            onSearch = { sheet = "search" },
            onBookmark = { controller.toggleBookmark() },
            onSettings = { sheet = "settings" },
            onTts = { if (controller.tts.speaking) controller.stopTts() else { controller.startTts(); session.chromeVisible = false } },
            onSeek = { controller.seek(it.toDouble()) },
        )
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = sheet != "settings")
    if (sheet != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = sheetState, containerColor = colors.bg.copy(alpha = 1f), contentColor = colors.fg,
            scrimColor = Color.Black.copy(alpha = if (sheet == "settings") 0.08f else 0.35f)) {
            when (sheet) {
                "settings" -> ReaderSettingsPanel(prefs, colors, reflow = true, isPdf = false, isComic = false) { f -> Graph.prefs.updateReader(f) }
                "contents" -> ContentsSheet(controller, annotations, colors) { sheet = null }
                "search" -> SearchSheet(controller, colors) { sheet = null }
            }
        }
    }

    controller.selectedHighlight?.let { h ->
        HighlightSheet(h, colors, controller, onDismiss = { controller.selectedHighlight = null })
    }
    controller.image?.let { bmp -> ImageViewer(bmp) { controller.image = null } }
    controller.footnote?.let { (text, url) ->
        ModalBottomSheet(onDismissRequest = { controller.footnote = null }, containerColor = colors.bg, contentColor = colors.fg) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("NOTE", color = colors.accent, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(10.dp))
                Text(text, color = colors.fg, fontFamily = com.jondude1.bookshelf.ui.Literata,
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()))
                TextButton(onClick = { controller.footnote = null; controller.onLink(url) }, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                    Text("Go to note", color = colors.accent)
                }
            }
        }
    }
    controller.noteDraft?.let { draft ->
        NoteDialog(colors, initial = draft.note ?: "", quote = draft.text,
            onSave = { note -> controller.addHighlight(draft.range, draft.text, note, draft.color, draft.id); controller.noteDraft = null },
            onDismiss = { controller.noteDraft = null })
    }
}

fun formatMinutes(m: Int): String = when {
    m < 60 -> "$m min"
    else -> "${m / 60} h ${m % 60} min"
}

@Composable
private fun ContentsSheet(controller: ReflowController, annotations: com.jondude1.bookshelf.data.Annotations, colors: ReaderColors, close: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().height(560.dp)) {
        SheetTabs(listOf("Contents", "Highlights (${annotations.highlights.size})", "Bookmarks (${annotations.bookmarks.size})"), tab, colors) { tab = it }
        when (tab) {
            0 -> {
                val toc = controller.book.toc
                val currentIdx = toc.indexOfLast { it.chapterIndex <= controller.chapter }
                val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIdx - 3).coerceAtLeast(0))
                LazyColumn(state = state) {
                    itemsIndexed(toc) { i, e ->
                        ListRow(e.title, null, colors, indent = e.depth.coerceAtMost(3), current = i == currentIdx, onClick = { controller.goToToc(e); close() })
                    }
                }
            }
            1 -> if (annotations.highlights.isEmpty()) EmptyHint("Long-press any passage and choose Highlight or Note.", colors) else LazyColumn {
                items(annotations.highlights.sortedWith(compareBy({ it.chapter }, { it.createdAt }))) { h ->
                    ListRow("“${h.text}”", listOfNotNull(h.note, h.chapterTitle).joinToString("  ·  "), colors,
                        onClick = { controller.goToHighlight(h); close() })
                }
                item {
                    TextButton(onClick = { controller.share(Graph.annotations.exportMarkdown(controller.meta)) }, modifier = Modifier.padding(12.dp)) {
                        Icon(Icons.Default.Share, null, tint = colors.accent); Spacer(Modifier.size(8.dp))
                        Text("Export highlights & notes", color = colors.accent)
                    }
                }
            }
            else -> if (annotations.bookmarks.isEmpty()) EmptyHint("Tap the bookmark icon at the top to save your place.", colors) else LazyColumn {
                items(annotations.bookmarks.sortedBy { it.fraction }) { b ->
                    ListRow(b.label, "${(b.fraction * 100).toInt()}% through", colors, onClick = { controller.goToBookmark(b); close() }) {
                        IconButton(onClick = { Graph.annotations.edit(controller.meta.id) { a -> a.copy(bookmarks = a.bookmarks - b) } }) {
                            Icon(Icons.Default.Close, "Remove", tint = colors.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyHint(text: String, colors: ReaderColors) {
    Text(text, color = colors.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
}

@Composable
private fun SearchSheet(controller: ReflowController, colors: ReaderColors, close: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run() { if (q.isBlank()) return; busy = true; scope.launch { hits = controller.search(q.trim()); busy = false } }
    Column(Modifier.fillMaxWidth().height(560.dp)) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text("Search this book") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.fg, unfocusedTextColor = colors.fg, focusedBorderColor = colors.accent, cursorColor = colors.accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (busy) CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally), color = colors.accent)
        hits?.let { list ->
            Text(if (list.isEmpty()) "No matches" else "${list.size}${if (list.size >= 300) "+" else ""} matches", color = colors.muted,
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            LazyColumn {
                items(list) { h -> ListRow(h.snippet, h.chapterTitle, colors, onClick = { controller.goToHit(q.trim(), h); close() }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HighlightSheet(h: Highlight, colors: ReaderColors, controller: ReflowController, onDismiss: () -> Unit) {
    var note by remember(h.id) { mutableStateOf(h.note ?: "") }
    ModalBottomSheet(onDismissRequest = { if (note != (h.note ?: "")) controller.updateHighlight(h.copy(note = note.ifBlank { null })); onDismiss() },
        containerColor = colors.bg, contentColor = colors.fg) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("“${h.text}”", color = colors.fg, style = MaterialTheme.typography.bodyLarge, maxLines = 8)
            Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("gold" to Color(0xFFE6B450), "green" to Color(0xFF78BE78), "blue" to Color(0xFF64A0E6), "rose" to Color(0xFFE6788C)).forEach { (name, c) ->
                    Box(Modifier.size(if (h.color == name) 34.dp else 28.dp).clip(CircleShape).background(c)
                        .clickable { controller.updateHighlight(h.copy(color = name)); controller.selectedHighlight = h.copy(color = name) })
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { controller.share("“${h.text}”\n— ${controller.meta.title}" + (h.note?.let { "\n\n$it" } ?: "")) }) { Icon(Icons.Default.Share, "Share", tint = colors.fg) }
                IconButton(onClick = { controller.deleteHighlight(h.id); onDismiss() }) { Icon(Icons.Default.Delete, "Delete", tint = colors.fg) }
            }
            OutlinedTextField(note, { note = it }, placeholder = { Text("Add a note") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.fg, unfocusedTextColor = colors.fg, focusedBorderColor = colors.accent, cursorColor = colors.accent))
            TextButton(onClick = { controller.updateHighlight(h.copy(note = note.ifBlank { null })); onDismiss() }, modifier = Modifier.align(Alignment.End)) {
                Text("Save", color = colors.accent)
            }
        }
    }
}

@Composable
fun NoteDialog(colors: ReaderColors, initial: String, quote: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bg, titleContentColor = colors.fg, textContentColor = colors.fg,
        title = { Text("Note") },
        text = {
            Column {
                Text("“$quote”", color = colors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.fg, unfocusedTextColor = colors.fg, focusedBorderColor = colors.accent, cursorColor = colors.accent))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save", color = colors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.muted) } },
    )
}


/** Full-screen illustration with pinch, pan and double-tap zoom. */
@Composable
private fun ImageViewer(bmp: android.graphics.Bitmap, onClose: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val state = androidx.compose.foundation.gestures.rememberTransformableState { z, pan, _ ->
        scale = (scale * z).coerceIn(1f, 6f)
        offset = if (scale <= 1.01f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(), contentDescription = "Illustration",
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { scale = if (scale > 1.05f) 1f else 2.5f; if (scale == 1f) offset = androidx.compose.ui.geometry.Offset.Zero })
                    }
                    .transformable(state)
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                .clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
        }
    }
}
