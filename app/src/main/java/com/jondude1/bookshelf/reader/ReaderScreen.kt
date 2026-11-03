package com.jondude1.bookshelf.reader

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jondude1.bookshelf.Graph
import com.jondude1.bookshelf.MainActivity
import com.jondude1.bookshelf.data.Book
import com.jondude1.bookshelf.data.BookFormat
import com.jondude1.bookshelf.data.ReadingProgress
import com.jondude1.bookshelf.ui.BookCover
import com.jondude1.bookshelf.ui.Literata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

/** Per-open-book state shared by both reader kinds. */
class ReadingSession(val book: Book, val onBack: () -> Unit) {
    var chromeVisible by mutableStateOf(false)
    var chapterPagesLeft by mutableIntStateOf(0)
    var bookPagesLeft by mutableIntStateOf(0)
    var showEnd by mutableStateOf(false)
    var keyHandler: ((Boolean) -> Unit)? = null
    var seekHandler: ((Float) -> Unit)? = null

    private var lastTurn = System.currentTimeMillis()
    private var nearEndShown = false

    fun toggleChrome() { chromeVisible = !chromeVisible }

    /** Counts reading time only while pages are actually turning (idle gaps over 3 min are ignored). */
    fun pageTurned() {
        val now = System.currentTimeMillis()
        val gap = (now - lastTurn) / 1000
        lastTurn = now
        Graph.stats.record(if (gap in 1..180) gap else 0, 1, book.id)
    }

    fun nearEnd() {
        if (nearEndShown) return
        nearEndShown = true
        maybeQueueNext()
    }

    fun reachedEnd() {
        Graph.progress.markFinished(book.id, true)
        Graph.stats.markFinished(book.id)
        maybeQueueNext()
        showEnd = true
    }

    private fun maybeQueueNext() {
        if (!Graph.prefs.reader.value.autoDownloadNext) return
        val next = Graph.library.nextInSeries(book) ?: return
        if (Graph.files.localFile(next) == null) Graph.files.download(next)
    }
}

private sealed interface Opened {
    data class Loading(val progress: Float) : Opened
    data class Failed(val message: String) : Opened
    data class Reflow(val book: ReflowBook, val progress: ReadingProgress?) : Opened
    data class Pages(val source: PageSource, val progress: ReadingProgress?) : Opened
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit, onOpenBook: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val view = LocalView.current
    val books by Graph.library.books.collectAsState()
    val meta = remember(books) { books.firstOrNull { it.id == bookId } }
    val prefs by Graph.prefs.reader.collectAsState()
    var hour by remember { mutableIntStateOf(LocalTime.now().hour) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); hour = LocalTime.now().hour } }
    val theme = prefs.effectiveTheme(hour)
    val colors = remember(theme) { ReaderColors.of(theme) }

    if (meta == null) {
        Box(Modifier.fillMaxSize().background(colors.bg), contentAlignment = Alignment.Center) {
            Text("This book isn't in your library yet. Refresh from Athena and try again.", color = colors.fg, modifier = Modifier.padding(32.dp))
        }
        return
    }

    val session = remember(bookId) { ReadingSession(meta, onBack) }
    var opened by remember(bookId) { mutableStateOf<Opened>(Opened.Loading(0f)) }

    LaunchedEffect(bookId) { Graph.scope.launch { com.jondude1.bookshelf.Shortcuts.publishContinue(context.applicationContext, meta) } }
    var attempt by remember(bookId) { mutableIntStateOf(0) }
    LaunchedEffect(bookId, attempt) {
        opened = Opened.Loading(0f)
        opened = runCatching {
            val progress = Graph.progress.resolveForOpen(bookId)
            val file = Graph.files.open(meta) { p -> opened = Opened.Loading(p) }
            withContext(Dispatchers.IO) {
                val fmt = if (meta.format == BookFormat.UNKNOWN) BookFormat.from(file.extension) else meta.format
                when (fmt) {
                    BookFormat.EPUB -> Opened.Reflow(EpubBook(file), progress)
                    BookFormat.MOBI, BookFormat.AZW3 -> Opened.Reflow(MobiBook(file), progress)
                    BookFormat.PDF -> Opened.Pages(PdfSource(file), progress)
                    BookFormat.CBZ -> Opened.Pages(CbzSource(file), progress)
                    BookFormat.CBR -> Opened.Pages(CbrSource(file, context.cacheDir), progress)
                    BookFormat.UNKNOWN -> throw IllegalStateException("BookShelf doesn't recognize this file type")
                }
            }
        }.getOrElse { Opened.Failed(it.message ?: "Couldn't open this book") }
    }

    // Immersive reading: system bars only with the controls
    DisposableEffect(session.chromeVisible) {
        val ctrl = WindowCompat.getInsetsController(activity.window, view)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (session.chromeVisible) ctrl.show(WindowInsetsCompat.Type.systemBars()) else ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.isAppearanceLightStatusBars = !colors.dark
        ctrl.isAppearanceLightNavigationBars = !colors.dark
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            val ctrl = WindowCompat.getInsetsController(activity.window, view)
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Graph.progress.flush()
        }
    }
    LaunchedEffect(prefs.brightness) {
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = if (prefs.brightness < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else prefs.brightness.coerceIn(0.01f, 1f)
        }
    }
    LaunchedEffect(prefs.keepScreenOn) {
        if (prefs.keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    // Volume keys
    DisposableEffect(prefs.volumeKeys) {
        MainActivity.volumeHandler = if (prefs.volumeKeys) { fwd -> session.keyHandler?.let { it(fwd); true } ?: false } else null
        onDispose { MainActivity.volumeHandler = null }
    }
    // Push progress when the app goes to the background
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) Graph.progress.flush() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    BackHandler(enabled = session.chromeVisible) { session.chromeVisible = false }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        when (val o = opened) {
            is Opened.Loading -> LoadingCover(meta, o.progress, colors)
            is Opened.Failed -> FailedView(meta, o.message, colors, onBack,
                onRetry = { attempt++ },
                onRedownload = { Graph.files.evict(meta); attempt++ })
            is Opened.Reflow -> ReflowReader(o.book, meta, o.progress, session, colors, prefs)
            is Opened.Pages -> PageReader(o.source, meta, o.progress, session, colors, prefs)
        }
        // Warm night-light filter
        val warm by animateFloatAsState(prefs.warmth, label = "warmth")
        if (warm > 0.01f) Box(Modifier.fillMaxSize().background(Color(0xFFFF8A2B).copy(alpha = warm * 0.24f)))
    }

    if (session.showEnd) {
        val next = remember { Graph.library.nextInSeries(meta) }
        ModalBottomSheet(onDismissRequest = { session.showEnd = false }, containerColor = colors.bg, contentColor = colors.fg) {
            EndOfBook(meta, next, colors,
                onNext = { n -> session.showEnd = false; onOpenBook(n.id) },
                onClose = { session.showEnd = false; onBack() })
        }
    }
}

@Composable
private fun LoadingCover(meta: Book, progress: Float, colors: ReaderColors) {
    Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BookCover(meta, Modifier.width(150.dp))
        Spacer(Modifier.height(28.dp))
        Text(meta.title, color = colors.fg, fontFamily = Literata, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        if (progress in 0.01f..0.99f) LinearProgressIndicator(progress = { progress }, color = colors.accent, trackColor = colors.line,
            modifier = Modifier.width(160.dp).clip(RoundedCornerShape(2.dp)))
        else LinearProgressIndicator(color = colors.accent, trackColor = colors.line, modifier = Modifier.width(160.dp).clip(RoundedCornerShape(2.dp)))
        Text(if (progress > 0.01f) "Fetching from Athena…" else "Opening…", color = colors.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun FailedView(meta: Book, message: String, colors: ReaderColors, onBack: () -> Unit, onRetry: () -> Unit, onRedownload: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(meta.title, color = colors.fg, fontFamily = Literata, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(message, color = colors.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.bg)) { Text("Try again") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRedownload) { Text("Download a fresh copy", color = colors.fg) }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.TextButton(onClick = onBack) { Text("Back to library", color = colors.muted) }
    }
}

@Composable
private fun EndOfBook(meta: Book, next: Book?, colors: ReaderColors, onNext: (Book) -> Unit, onClose: () -> Unit) {
    val downloads by Graph.files.downloads.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("The End", color = colors.fg, fontFamily = Literata, style = MaterialTheme.typography.headlineMedium)
        Text("You finished ${meta.title}. It's marked as read on Athena.", color = colors.muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
        if (next != null) {
            Text("UP NEXT IN ${next.primarySeries?.name?.uppercase() ?: "THE SERIES"}", color = colors.accent, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                BookCover(next, Modifier.width(84.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(next.title, color = colors.fg, fontFamily = Literata, style = MaterialTheme.typography.titleMedium)
                    next.primarySeries?.sequence?.let { Text("Book $it", color = colors.muted, style = MaterialTheme.typography.bodySmall) }
                    val d = downloads[next.id]
                    if (d is com.jondude1.bookshelf.data.DownloadState.Running) Text("Downloading…", color = colors.muted, style = MaterialTheme.typography.bodySmall)
                    if (d is com.jondude1.bookshelf.data.DownloadState.Done) Text("Ready offline", color = colors.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = { onNext(next) }, colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.bg),
                modifier = Modifier.fillMaxWidth()) { Text("Start reading") }
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Back to library", color = colors.fg) }
        Spacer(Modifier.size(4.dp))
    }
}
