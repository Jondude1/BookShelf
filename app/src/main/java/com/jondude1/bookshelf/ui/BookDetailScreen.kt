package com.jondude1.bookshelf.ui

import android.content.Intent
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import coil.compose.AsyncImage
import com.jondude1.bookshelf.Graph
import com.jondude1.bookshelf.data.Book
import com.jondude1.bookshelf.data.DownloadState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookDetailScreen(bookId: String, onBack: () -> Unit, onRead: (Book) -> Unit, onOpen: (Book) -> Unit, onSeries: (String) -> Unit, onAuthor: (String) -> Unit, compact: Boolean = false) {
    val context = LocalContext.current
    val books by Graph.library.books.collectAsState()
    val book = books.firstOrNull { it.id == bookId } ?: return
    val progress by Graph.progress.all.collectAsState()
    val offline by Graph.files.offline.collectAsState()
    val downloads by Graph.files.downloads.collectAsState()
    val annotations by Graph.annotations.flow(bookId).collectAsState()
    var menu by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600 && !compact

    LaunchedEffect(bookId) { Graph.library.detail(bookId) }

    val p = progress[bookId]
    val seriesList = book.primarySeries?.let { Graph.library.seriesBooks(it.name) }.orEmpty()
    val byAuthor = remember(books, book.author) {
        val first = book.author.split(",").first().trim()
        books.filter { it.id != bookId && first.isNotBlank() && it.author.contains(first) && it !in seriesList }.take(20)
    }
    val description = remember(book.description) {
        book.description?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Box(Modifier.fillMaxWidth()) {
                // Blurred artwork backdrop
                AsyncImage(
                    model = coverRequest(context, book.id, 300), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(if (wide) 360.dp else 420.dp).then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(48.dp) else Modifier),
                )
                Box(Modifier.fillMaxWidth().height(if (wide) 360.dp else 420.dp).background(
                    Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.55f), MaterialTheme.colorScheme.background))))
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Spacer(Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(if (p?.finished == true) "Mark as unread" else "Mark as finished") }, onClick = {
                                    Graph.progress.markFinished(bookId, p?.finished != true)
                                    if (p?.finished != true) Graph.stats.markFinished(bookId)
                                    menu = false
                                })
                                if (annotations.highlights.isNotEmpty()) DropdownMenuItem(text = { Text("Export highlights & notes") }, onClick = {
                                    val i = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_TEXT, Graph.annotations.exportMarkdown(book))
                                        .putExtra(Intent.EXTRA_SUBJECT, "Notes — ${book.title}")
                                    context.startActivity(Intent.createChooser(i, "Export notes"))
                                    menu = false
                                })
                                if (book.id in offline) DropdownMenuItem(text = { Text("Remove download") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                    onClick = { Graph.files.remove(book.id); menu = false })
                            }
                        }
                    }
                    if (wide) {
                        Row(Modifier.padding(horizontal = 32.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom) {
                            BookCover(book, Modifier.width(200.dp), corner = 10.dp, elevation = 18.dp, width = 700)
                            Spacer(Modifier.width(32.dp))
                            Column(Modifier.weight(1f)) { Header(book, onSeries, onAuthor, center = false) }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            BookCover(book, Modifier.width(178.dp), corner = 10.dp, elevation = 18.dp, width = 700)
                            Spacer(Modifier.height(22.dp))
                            Header(book, onSeries, onAuthor, center = true)
                        }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).let { if (wide) it.widthIn(max = 640.dp) else it },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onRead(book) }, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(when {
                        p?.finished == true -> "Read again"
                        p != null && p.fraction > 0.001 -> "Continue · ${(p.fraction * 100).toInt()}%"
                        else -> "Start reading"
                    })
                }
                val d = downloads[book.id]
                FilledTonalIconButton(onClick = { if (book.id !in offline) Graph.files.download(book) }, modifier = Modifier.size(52.dp)) {
                    when {
                        book.id in offline -> Icon(Icons.Default.DownloadDone, "Downloaded", tint = MaterialTheme.colorScheme.primary)
                        d is DownloadState.Running -> CircularProgressIndicator(progress = { d.progress.coerceAtLeast(0.02f) }, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        else -> Icon(Icons.Default.Download, "Download for offline")
                    }
                }
            }
            (downloads[book.id] as? DownloadState.Failed)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp))
            }
            if (p != null && !p.finished && p.fraction > 0.001) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxWidth(p.fraction.toFloat()).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
        }

        item {
            FlowRow(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(book.format.label)
                if (book.fileSize > 0) Pill(formatBytes(book.fileSize))
                book.publishedYear?.takeIf { it.isNotBlank() }?.let { Pill(it.take(4)) }
                book.publisher?.takeIf { it.isNotBlank() }?.let { Pill(it) }
                book.language?.takeIf { it.isNotBlank() }?.let { Pill(it) }
                val secs = Graph.stats.data.collectAsState().value.books[book.id] ?: 0
                if (secs >= 60) Pill("${formatDuration(secs)} read")
                if (annotations.highlights.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.EditNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(" ${annotations.highlights.size} highlights", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (p?.finished == true) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(" Finished", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (!description.isNullOrBlank()) item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).animateContentSize().clickable { expanded = !expanded }) {
                Text("About this book", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                Text(description, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Literata), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                    maxLines = if (expanded) Int.MAX_VALUE else 6)
                if (description.length > 320) Text(if (expanded) "Show less" else "Read more", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            }
        }

        if (book.genres.isNotEmpty()) item {
            FlowRow(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                book.genres.forEach { Pill(it, color = MaterialTheme.colorScheme.secondaryContainer) }
            }
        }

        if (seriesList.size > 1) item {
            val s = book.primarySeries!!
            SectionHeader(s.name, "${seriesList.size} books in this series", action = "All", onAction = { onSeries(s.name) })
            BookShelfRow(seriesList, progress, offline, tileWidth = 104.dp, onOpen = onOpen)
        }
        if (byAuthor.isNotEmpty()) item {
            val name = book.author.split(",").first().trim()
            SectionHeader("More by $name", action = "All", onAction = { onAuthor(name) })
            BookShelfRow(byAuthor, progress, offline, tileWidth = 104.dp, onOpen = onOpen)
        }
    }
}

@Composable
private fun Header(book: Book, onSeries: (String) -> Unit, onAuthor: (String) -> Unit, center: Boolean) {
    val align = if (center) TextAlign.Center else TextAlign.Start
    Column(horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        book.primarySeries?.let { s ->
            Text((s.name + (s.sequence?.let { " · Book $it" } ?: "")).uppercase(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, textAlign = align, modifier = Modifier.clickable { onSeries(s.name) }.padding(bottom = 6.dp))
        }
        Text(book.title, style = MaterialTheme.typography.displaySmall, textAlign = align)
        book.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align, modifier = Modifier.padding(top = 4.dp)) }
        if (book.author.isNotBlank()) Text("by ${book.author}", style = MaterialTheme.typography.bodyLarge, color = Color.Unspecified, textAlign = align,
            modifier = Modifier.padding(top = 8.dp).clickable { onAuthor(book.author.split(",").first().trim()) })
    }
}
