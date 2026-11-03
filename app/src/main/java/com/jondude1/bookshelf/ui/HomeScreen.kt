package com.jondude1.bookshelf.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jondude1.bookshelf.Graph
import com.jondude1.bookshelf.data.Book
import com.jondude1.bookshelf.data.LoadState
import com.jondude1.bookshelf.data.ReadingProgress
import com.jondude1.bookshelf.data.Route
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Book) -> Unit, onRead: (Book) -> Unit, onSeries: (String) -> Unit, onLibrary: () -> Unit, onStats: () -> Unit, onCollection: (String) -> Unit = {}, onNotes: () -> Unit = {}) {
    val collections by Graph.library.collections.collectAsState()
    val books by Graph.library.books.collectAsState()
    val progress by Graph.progress.all.collectAsState()
    val offline by Graph.files.offline.collectAsState()
    val loadState by Graph.library.state.collectAsState()
    val route by Graph.client.route.collectAsState()
    val stats by Graph.stats.data.collectAsState()
    val readerPrefs by Graph.prefs.reader.collectAsState()
    val user = Graph.prefs.server.collectAsState().value.username
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    LaunchedEffect(Unit) {
        Graph.library.refreshIfStale()
        Graph.progress.syncFromServer()
    }

    val byId = remember(books) { books.associateBy { it.id } }
    val reading = remember(books, progress) {
        progress.values.filter { !it.finished && it.fraction > 0.001 }.sortedByDescending { it.updatedAt }.mapNotNull { byId[it.itemId] }.distinctBy { it.id }
    }
    val upNext = remember(books, progress) {
        val finishedSeries = books.filter { progress[it.id]?.finished == true }.mapNotNull { it.primarySeries?.name }.distinct()
        finishedSeries.mapNotNull { name ->
            Graph.library.seriesBooks(name).firstOrNull { b -> progress[b.id]?.let { it.finished || it.fraction > 0.001 } != true }
        }.filter { it !in reading }.distinctBy { it.id }
    }
    val recent = remember(books) { books.distinctBy { it.id }.sortedByDescending { it.addedAt }.take(24) }
    val series = remember(books) {
        books.filter { it.primarySeries != null }.groupBy { it.primarySeries!!.name }
            .filter { it.value.size >= 2 }
            .entries.sortedByDescending { e -> e.value.maxOf { it.addedAt } }.take(14)
            .map { (n, _) -> n to Graph.library.seriesBooks(n) }
    }
    val discover = remember(books, progress) {
        val seed = LocalDate.now().toEpochDay()
        books.distinctBy { it.id }.filter { progress[it.id] == null }.shuffled(java.util.Random(seed)).take(16)
    }
    val onDevice = remember(books, offline) { books.distinctBy { it.id }.filter { it.id in offline } }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; Graph.library.refresh(); Graph.progress.syncFromServer(); refreshing = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 20.dp, end = 20.dp, top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting() + if (user.isNotBlank()) ", ${user.replaceFirstChar { it.uppercase() }}" else "", style = MaterialTheme.typography.headlineLarge)
                        Text("${books.size} books on Athena", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.IconButton(onClick = onNotes) { Icon(Icons.Default.EditNote, "Notes") }
                    ConnectionChip(route)
                }
                (loadState as? LoadState.Loading)?.let { s ->
                    if (books.isEmpty() || s.total > 0) LinearProgressIndicator(
                        progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).clip(CircleShape))
                }
                (loadState as? LoadState.Error)?.let { e ->
                    Text(if (books.isEmpty()) e.message else "Offline — showing your saved library. ${e.message}",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }

            if (reading.isNotEmpty()) item {
                SectionHeader("Continue reading")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(reading, key = { it.id }) { b -> HeroCard(b, progress[b.id], if (wide) 520 else 320, onClick = { onRead(b) }, onDetails = { onOpen(b) }) }
                }
            }

            item {
                GoalCard(
                    todaySeconds = stats.days[LocalDate.now().toString()]?.seconds ?: 0,
                    goalMinutes = readerPrefs.dailyGoalMinutes,
                    streak = Graph.stats.streak(),
                    onClick = onStats,
                )
            }

            if (upNext.isNotEmpty()) item {
                SectionHeader("Up next in your series")
                BookShelfRow(upNext, progress, offline, onOpen = onOpen)
            }
            if (recent.isNotEmpty()) item {
                SectionHeader("Recently added", action = "Library", onAction = onLibrary)
                BookShelfRow(recent, progress, offline, onOpen = onOpen)
            }
            if (series.isNotEmpty()) item {
                SectionHeader("Series")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(series, key = { it.first }) { (name, list) ->
                        Column(Modifier.width(150.dp).clickable { onSeries(name) }) {
                            SeriesStack(list)
                            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                            val done = list.count { progress[it.id]?.finished == true }
                            Text("${list.size} books" + if (done > 0) " · $done read" else "", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (collections.isNotEmpty()) item {
                SectionHeader("Collections")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(collections, key = { it.id }) { c ->
                        val list = c.bookIds.mapNotNull { byId[it] }
                        Column(Modifier.width(150.dp).clickable { onCollection(c.id) }) {
                            SeriesStack(list)
                            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                            Text("${list.size} books", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (onDevice.isNotEmpty()) item {
                SectionHeader("On this device", "Readable without Athena")
                BookShelfRow(onDevice, progress, offline, onOpen = onOpen)
            }
            if (discover.isNotEmpty()) item {
                SectionHeader("From your shelves", "Unread picks, fresh each day")
                BookShelfRow(discover, progress, offline, onOpen = onOpen)
            }
            if (books.isEmpty() && loadState !is LoadState.Loading) item {
                Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No ebooks found yet", style = MaterialTheme.typography.titleLarge)
                    Text("Add EPUB, PDF, MOBI, AZW3, CBZ or CBR files to a book library in Audiobookshelf, then pull down to refresh.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { scope.launch { Graph.library.refresh() } }) { Text("Refresh") }
                }
            }
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun ConnectionChip(route: Route) {
    val (icon, label) = when (route) {
        Route.LAN -> Icons.Default.Home to "Home"
        Route.REMOTE -> Icons.Default.Public to "Tailscale"
        Route.OFFLINE -> Icons.Default.CloudOff to "Offline"
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(14.dp), tint = if (route == Route.OFFLINE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun HeroCard(book: Book, progress: ReadingProgress?, widthDp: Int, onClick: () -> Unit, onDetails: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.width(widthDp.dp).height(196.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        AsyncImage(
            model = coverRequest(context, book.id, 300), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(36.dp) else Modifier),
        )
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xE6101113), Color(0xB3101113), Color(0x80101113)))))
        Row(Modifier.matchParentSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, Modifier.height(164.dp), corner = 8.dp, elevation = 10.dp, width = 400)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, color = Color(0xFFF3EEE4), maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB9B3A8), maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp).clickable(onClick = onDetails))
                Spacer(Modifier.weight(1f))
                val f = progress?.fraction?.toFloat() ?: 0f
                Text("${(f * 100).toInt()}% read", style = MaterialTheme.typography.labelMedium, color = Brand.Gold)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(f.coerceIn(0.02f, 1f)).height(4.dp).clip(CircleShape).background(Brand.Gold))
                }
                Spacer(Modifier.height(12.dp))
                Text("Continue  →", style = MaterialTheme.typography.labelLarge, color = Color(0xFFF3EEE4))
            }
        }
    }
}

@Composable
private fun GoalCard(todaySeconds: Long, goalMinutes: Int, streak: Int, onClick: () -> Unit) {
    val minutes = (todaySeconds / 60).toInt()
    val frac = (minutes.toFloat() / goalMinutes.coerceAtLeast(1)).coerceIn(0f, 1f)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val s = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                val inset = 3.dp.toPx()
                drawArc(track, -90f, 360f, false, topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2), style = s)
                drawArc(primary, -90f, 360f * frac, false, topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2), style = s)
            }
            Text("$minutes", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(if (frac >= 1f) "Daily goal reached" else "$minutes of $goalMinutes minutes today", style = MaterialTheme.typography.titleSmall)
            Text(if (streak > 0) "$streak-day reading streak" else "Read a little today to start a streak",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Stats", style = MaterialTheme.typography.labelLarge, color = primary)
    }
}
