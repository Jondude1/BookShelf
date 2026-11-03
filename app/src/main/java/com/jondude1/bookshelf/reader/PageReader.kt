package com.jondude1.bookshelf.reader

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jondude1.bookshelf.Graph
import com.jondude1.bookshelf.data.Book
import com.jondude1.bookshelf.data.Bookmark
import com.jondude1.bookshelf.data.ReaderPrefs
import com.jondude1.bookshelf.data.ReadingProgress
import com.jondude1.bookshelf.data.SpreadMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Swipeable, zoomable reader for PDFs and comics. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageReader(source: PageSource, meta: Book, initial: ReadingProgress?, session: ReadingSession, colors: ReaderColors, prefs: ReaderPrefs) {
    val cfg = LocalConfiguration.current
    val landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val spread = when (prefs.spread) {
        SpreadMode.SINGLE -> false
        SpreadMode.DOUBLE -> true
        SpreadMode.AUTO -> landscape && cfg.screenWidthDp >= 700
    } && source.pageCount > 2
    // In spreads, page 0 (the cover) stands alone, then pairs follow
    val slots = if (spread) 1 + (source.pageCount - 1 + 1) / 2 else source.pageCount
    fun slotOf(page: Int) = if (!spread) page else if (page == 0) 0 else (page + 1) / 2
    fun pagesOf(slot: Int): List<Int> = if (!spread) listOf(slot) else if (slot == 0) listOf(0) else listOf(slot * 2 - 1, slot * 2).filter { it < source.pageCount }

    val startPage = remember {
        initial?.location?.split(':')?.takeIf { it.size == 3 && it[1] == "p" }?.get(2)?.toIntOrNull()
            ?: if (initial != null && !initial.finished) (initial.fraction * (source.pageCount - 1)).toInt() else 0
    }
    val pager = rememberPagerState(initialPage = slotOf(startPage.coerceIn(0, source.pageCount - 1))) { slots }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(startPage) }

    LaunchedEffect(spread) {
        snapshotFlow { pager.currentPage }.collect { slot ->
            val p = pagesOf(slot).first()
            if (p != currentPage) session.pageTurned()
            currentPage = p
            val f = if (source.pageCount <= 1) 1.0 else p.toDouble() / (source.pageCount - 1)
            Graph.progress.update(meta.id, f, "bs1:p:$p")
            session.chapterPagesLeft = 0
            session.bookPagesLeft = source.pageCount - p - 1
            if (slot == slots - 1) session.nearEnd()
        }
    }

    fun go(delta: Int) {
        val t = pager.currentPage + delta
        if (t in 0 until slots) scope.launch { pager.animateScrollToPage(t) } else if (delta > 0) session.reachedEnd()
    }
    val rtl = prefs.comicRightToLeft && meta.format.isComic
    DisposableEffect(rtl) {
        session.keyHandler = { fwd -> go(if (fwd) 1 else -1) }
        session.seekHandler = { f -> scope.launch { pager.scrollToPage(slotOf((f * (source.pageCount - 1)).toInt())) } }
        onDispose { session.keyHandler = null }
    }
    DisposableEffect(Unit) { onDispose { runCatching { source.close() } } }

    val invert = meta.format.name == "PDF" && prefs.invertPdfAtNight && colors.dark
    val pageBg = if (meta.format.isComic) Color.Black.takeIf { colors.dark } ?: colors.bg else colors.bg
    val annotations by Graph.annotations.flow(meta.id).collectAsState()
    val bookmarked = annotations.bookmarks.any { it.location == "bs1:p:$currentPage" }
    var sheet by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(pageBg)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx().toInt() }
            HorizontalPager(
                state = pager,
                reverseLayout = rtl,
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
                key = { it },
            ) { slot ->
                ZoomBox(
                    onZoomChange = { zoomed = it },
                    onTap = { x ->
                        val w = widthPx.toFloat()
                        when {
                            prefs.tapZones && x < w * 0.28f -> go(if (rtl) 1 else -1)
                            prefs.tapZones && x > w * 0.72f -> go(if (rtl) -1 else 1)
                            else -> session.toggleChrome()
                        }
                    },
                ) {
                    val pages = pagesOf(slot).let { if (rtl) it.reversed() else it }
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        pages.forEach { p ->
                            PageImage(source, p, widthPx / pages.size, invert, Modifier.weight(1f, fill = false).fillMaxHeight())
                        }
                    }
                }
            }
        }

        if (!session.chromeVisible) ReaderFooter(colors.copy(fg = if (pageBg == Color.Black) Color.White else colors.fg),
            left = "Page ${currentPage + 1} of ${source.pageCount}", right = "${((currentPage + 1) * 100 / source.pageCount)}%",
            top = null, showClock = prefs.showClock, topInset = 30)

        ReaderChrome(
            visible = session.chromeVisible, colors = colors, title = meta.title, subtitle = meta.author,
            fraction = if (source.pageCount <= 1) 1f else currentPage.toFloat() / (source.pageCount - 1),
            pageLabel = "Page ${currentPage + 1} of ${source.pageCount}", timeLabel = null,
            bookmarked = bookmarked, ttsAvailable = false, ttsPlaying = false,
            onBack = session.onBack, onContents = { sheet = "contents" },
            onSearch = if (source is PdfSource && android.os.Build.VERSION.SDK_INT >= 35) ({ sheet = "search" }) else null,
            onBookmark = {
                Graph.annotations.edit(meta.id) { a ->
                    val ex = a.bookmarks.firstOrNull { it.location == "bs1:p:$currentPage" }
                    if (ex != null) a.copy(bookmarks = a.bookmarks - ex)
                    else a.copy(bookmarks = a.bookmarks + Bookmark(UUID.randomUUID().toString(), "bs1:p:$currentPage", "Page ${currentPage + 1}",
                        currentPage.toDouble() / (source.pageCount - 1).coerceAtLeast(1)))
                }
            },
            onSettings = { sheet = "settings" }, onTts = {},
            onSeek = { f -> scope.launch { pager.scrollToPage(slotOf((f * (source.pageCount - 1)).toInt())) } },
        )
    }

    if (sheet != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.bg, contentColor = colors.fg) {
            when (sheet) {
                "settings" -> ReaderSettingsPanel(prefs, colors, reflow = false, isPdf = source is PdfSource, isComic = meta.format.isComic) { Graph.prefs.updateReader(it) }
                "contents" -> Column(Modifier.fillMaxWidth().height(520.dp)) {
                    SheetTabs(listOf("Bookmarks (${annotations.bookmarks.size})"), 0, colors) {}
                    if (annotations.bookmarks.isEmpty()) EmptyHint("Tap the bookmark icon at the top to save a page.", colors)
                    LazyColumn {
                        items(annotations.bookmarks.sortedBy { it.fraction }) { b ->
                            ListRow(b.label, null, colors, onClick = {
                                b.location.split(':').getOrNull(2)?.toIntOrNull()?.let { p -> scope.launch { pager.scrollToPage(slotOf(p)) } }
                                sheet = null
                            }) {
                                IconButton(onClick = { Graph.annotations.edit(meta.id) { a -> a.copy(bookmarks = a.bookmarks - b) } }) {
                                    Icon(Icons.Default.Close, "Remove", tint = colors.muted)
                                }
                            }
                        }
                    }
                }
                "search" -> PdfSearch(source, colors) { p -> scope.launch { pager.scrollToPage(slotOf(p)) }; sheet = null }
            }
        }
    }
}

@Composable
private fun PdfSearch(source: PageSource, colors: ReaderColors, onPick: (Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Pair<Int, String>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().height(520.dp)) {
        OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Search this PDF") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                busy = true
                scope.launch {
                    hits = withContext(Dispatchers.IO) {
                        (0 until source.pageCount).mapNotNull { i ->
                            val t = source.pageText(i) ?: return@mapNotNull null
                            val idx = t.indexOf(q, ignoreCase = true)
                            if (idx < 0) null else i to t.substring((idx - 50).coerceAtLeast(0), (idx + q.length + 70).coerceAtMost(t.length)).replace('\n', ' ')
                        }
                    }
                    busy = false
                }
            }),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.fg, unfocusedTextColor = colors.fg, focusedBorderColor = colors.accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        if (busy) CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally), color = colors.accent)
        hits?.let { list ->
            if (list.isEmpty()) EmptyHint("No matches", colors)
            LazyColumn { items(list) { (p, s) -> ListRow(s, "Page ${p + 1}", colors, onClick = { onPick(p) }) } }
        }
    }
}

@Composable
private fun PageImage(source: PageSource, index: Int, targetWidth: Int, invert: Boolean, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, index, targetWidth) {
        value = source.render(index, (targetWidth * 1.6f).toInt())
    }
    val b = bmp
    if (b == null) {
        Box(modifier.aspectRatio(source.aspect(index)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.Gray)
        }
    } else {
        Image(
            bitmap = b.asImageBitmap(), contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.Fit,
            colorFilter = if (invert) ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))) else null,
            modifier = modifier.aspectRatio(b.width.toFloat() / b.height, matchHeightConstraintsFirst = true),
        )
    }
}

/** Pinch and double-tap zoom that leaves single-finger swipes to the pager. */
@Composable
private fun ZoomBox(onZoomChange: (Boolean) -> Unit, onTap: (Float) -> Unit, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { p ->
                        if (scale > 1.05f) { scale = 1f; offset = Offset.Zero; onZoomChange(false) }
                        else { scale = 2.5f; offset = Offset((size.width / 2f - p.x) * 1.5f, (size.height / 2f - p.y) * 1.5f); onZoomChange(true) }
                    },
                    onTap = { p -> onTap(p.x) },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2 || scale > 1.01f) {
                            val z = event.calculateZoom()
                            val pan = event.calculatePan()
                            val ns = (scale * z).coerceIn(1f, 5f)
                            val maxX = size.width * (ns - 1) / 2f
                            val maxY = size.height * (ns - 1) / 2f
                            offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            scale = ns
                            if (ns <= 1.01f) { scale = 1f; offset = Offset.Zero }
                            onZoomChange(scale > 1.01f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

private fun androidx.compose.ui.input.pointer.PointerInputChange.positionChanged() = position != previousPosition

