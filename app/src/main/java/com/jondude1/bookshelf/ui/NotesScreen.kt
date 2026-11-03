package com.jondude1.bookshelf.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jondude1.bookshelf.Graph
import com.jondude1.bookshelf.data.Book
import com.jondude1.bookshelf.data.Highlight

private val swatch = mapOf("gold" to Color(0xFFE6B450), "green" to Color(0xFF78BE78), "blue" to Color(0xFF64A0E6), "rose" to Color(0xFFE6788C))

/** Every highlight and note across the library, newest books first. */
@Composable
fun NotesScreen(onBack: () -> Unit, onRead: (Book) -> Unit, onOpen: (Book) -> Unit) {
    val context = LocalContext.current
    val books by Graph.library.books.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(books) {
        val byId = books.associateBy { it.id }
        Graph.annotations.allBooksWithNotes().mapNotNull { id ->
            val b = byId[id] ?: return@mapNotNull null
            val hs = Graph.annotations.flow(id).value.highlights
            if (hs.isEmpty()) null else b to hs.sortedWith(compareBy({ it.chapter }, { it.createdAt }))
        }.sortedByDescending { (_, hs) -> hs.maxOf { it.createdAt } }
    }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) groups else groups.mapNotNull { (b, hs) ->
        val f = hs.filter { it.text.lowercase().contains(q) || (it.note?.lowercase()?.contains(q) == true) || b.title.lowercase().contains(q) }
        if (f.isEmpty()) null else b to f
    }
    val total = groups.sumOf { it.second.size }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            if (total > 0) IconButton(onClick = {
                val md = groups.joinToString("\n\n---\n\n") { (b, _) -> Graph.annotations.exportMarkdown(b) }
                val i = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_TEXT, md).putExtra(Intent.EXTRA_SUBJECT, "BookShelf notes")
                context.startActivity(Intent.createChooser(i, "Export all notes"))
            }) { Icon(Icons.Default.Share, "Export all") }
        }
        Text("Notes", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 20.dp))
        Text("$total highlights in ${groups.size} books", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        if (total > 0) OutlinedTextField(query, { query = it }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Search highlights and notes") }, shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
        if (total == 0) Text("Long-press any passage while reading and choose Highlight or Note. Everything you mark shows up here.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            shown.forEach { (book, hs) ->
                item(key = "h-" + book.id) {
                    Row(Modifier.fillMaxWidth().clickable { onOpen(book) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookCover(book, Modifier.width(40.dp), corner = 3.dp, elevation = 2.dp, width = 150)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${hs.size} highlight${if (hs.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(hs, key = { "n-" + it.id }) { h -> NoteCard(h) { onRead(book) } }
            }
        }
    }
}

@Composable
private fun NoteCard(h: Highlight, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick)) {
        Box(Modifier.width(4.dp).height(64.dp).background(swatch[h.color] ?: Brand.Gold))
        Column(Modifier.padding(14.dp)) {
            Text("“${h.text}”", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Literata), maxLines = 6, overflow = TextOverflow.Ellipsis)
            h.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            h.chapterTitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
