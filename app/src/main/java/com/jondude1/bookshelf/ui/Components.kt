package com.jondude1.bookshelf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jondude1.bookshelf.Graph
import com.jondude1.bookshelf.data.Book
import com.jondude1.bookshelf.data.ReadingProgress
import kotlin.math.abs

private val coverPalettes = listOf(
    Color(0xFF2E3A46) to Color(0xFF151A20), Color(0xFF4A3528) to Color(0xFF1E1511), Color(0xFF2F4237) to Color(0xFF121A15),
    Color(0xFF45304A) to Color(0xFF1A121C), Color(0xFF4A4128) to Color(0xFF1E1A10), Color(0xFF283E4A) to Color(0xFF0F181D),
)

fun coverRequest(context: android.content.Context, id: String, width: Int = 600) = ImageRequest.Builder(context)
    .data(Graph.client.coverUrl(id, width))
    .memoryCacheKey("cover-$id-$width")
    .diskCacheKey("cover-$id-$width")
    .crossfade(true)
    .build()

/** A book cover with a typeset fallback when Athena has no artwork. */
@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier, corner: Dp = 6.dp, elevation: Dp = 6.dp, width: Int = 500) {
    val context = LocalContext.current
    var loaded by remember(book.id) { mutableStateOf(false) }
    Box(
        modifier.aspectRatio(2f / 3f).shadow(elevation, RoundedCornerShape(corner), clip = false)
            .clip(RoundedCornerShape(corner))
    ) {
        if (!loaded) GeneratedCover(book)
        AsyncImage(
            model = coverRequest(context, book.id, width),
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            onSuccess = { loaded = true },
            modifier = Modifier.fillMaxSize(),
        )
        // subtle spine highlight
        Box(Modifier.fillMaxHeight().width(3.dp).background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent))))
    }
}

@Composable
private fun GeneratedCover(book: Book) {
    val (a, b) = coverPalettes[abs(book.id.hashCode()) % coverPalettes.size]
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(a, b))).padding(12.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(28.dp).height(1.dp).background(Brand.Gold.copy(alpha = 0.7f)))
            Spacer(Modifier.height(10.dp))
            Text(book.title, color = Color(0xFFF1E9D8), fontFamily = Literata, fontSize = 15.sp, lineHeight = 19.sp,
                textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(book.author.uppercase(), color = Brand.Gold.copy(alpha = 0.85f), fontSize = 8.5.sp, letterSpacing = 1.2.sp,
                    textAlign = TextAlign.Center, maxLines = 2, fontFamily = Inter)
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(28.dp).height(1.dp).background(Brand.Gold.copy(alpha = 0.7f)))
        }
    }
}

/** Grid / shelf tile: cover with progress + offline marks, then title and author. */
@Composable
fun BookTile(book: Book, progress: ReadingProgress?, offline: Boolean, modifier: Modifier = Modifier, showText: Boolean = true, onClick: () -> Unit) {
    Column(modifier.bookActions(book, onClick)) {
        Box {
            BookCover(book, Modifier.fillMaxWidth())
            if (progress?.finished == true) Badge(Icons.Default.Check, Modifier.align(Alignment.TopEnd).padding(6.dp))
            else if (offline) Badge(Icons.Default.DownloadDone, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (progress != null && !progress.finished && progress.fraction > 0.005) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)))
                    Box(Modifier.fillMaxWidth(progress.fraction.toFloat().coerceIn(0.03f, 1f)).height(3.dp).clip(CircleShape).background(Brand.Gold))
                }
            }
        }
        if (showText) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
            if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Box(modifier.size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Brand.Gold, modifier = Modifier.size(14.dp))
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null && onAction != null) Row(
            Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onAction).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun BookShelfRow(books: List<Book>, progress: Map<String, ReadingProgress>, offline: Set<String>, tileWidth: Dp = 118.dp, onOpen: (Book) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(books.distinctBy { it.id }, key = { it.id }) { b ->
            BookTile(b, progress[b.id], b.id in offline, Modifier.width(tileWidth)) { onOpen(b) }
        }
    }
}

/** Three overlapping covers for a series card. */
@Composable
fun SeriesStack(books: List<Book>, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(1.05f)) {
        books.take(3).reversed().forEachIndexed { i, b ->
            val n = books.take(3).size
            val idx = n - 1 - i
            BookCover(b, Modifier.fillMaxWidth(0.72f).offset(x = (idx * 14).dp, y = (idx * 6).dp).align(Alignment.TopStart), elevation = 4.dp, width = 300)
        }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 5.dp))
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.1f MB".format(b / (1024.0 * 1024))
    b >= 1L shl 10 -> "${b / 1024} KB"
    else -> "$b B"
}

@Suppress("unused") private val boldWeight = FontWeight.Bold

/** App-wide "open this book in the reader" action, provided by the navigation host. */
val LocalReadBook = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

/** Tap opens details; long-press shows quick actions (read, download, mark finished). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.bookActions(book: Book, onClick: () -> Unit): Modifier {
    var menu by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val read = LocalReadBook.current
    val offline by Graph.files.offline.collectAsState()
    val progress by Graph.progress.all.collectAsState()
    val finished = progress[book.id]?.finished == true
    if (menu) {
        androidx.compose.material3.DropdownMenu(expanded = true, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text(if ((progress[book.id]?.fraction ?: 0.0) > 0.001 && !finished) "Continue reading" else "Read now") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) }, onClick = { menu = false; read(book.id) })
            if (book.id in offline) androidx.compose.material3.DropdownMenuItem(text = { Text("Remove download") },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { menu = false; Graph.files.remove(book.id) })
            else androidx.compose.material3.DropdownMenuItem(text = { Text("Download") },
                leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { menu = false; Graph.files.download(book) })
            androidx.compose.material3.DropdownMenuItem(text = { Text(if (finished) "Mark as unread" else "Mark as finished") },
                leadingIcon = { Icon(if (finished) Icons.Default.Replay else Icons.Default.Check, null) }, onClick = {
                    menu = false
                    Graph.progress.markFinished(book.id, !finished)
                    if (!finished) Graph.stats.markFinished(book.id)
                })
            book.primarySeries?.let { s ->
                androidx.compose.material3.DropdownMenuItem(text = { Text("Download rest of ${s.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.LibraryAdd, null) }, onClick = {
                        menu = false
                        Graph.library.seriesBooks(s.name).filter { it.id !in offline && progress[it.id]?.finished != true }.forEach { Graph.files.download(it) }
                    })
            }
        }
    }
    return this.combinedClickable(onClick = onClick, onLongClick = {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        menu = true
    })
}
