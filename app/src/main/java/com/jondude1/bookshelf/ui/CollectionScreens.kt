package com.jondude1.bookshelf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jondude1.bookshelf.Graph
import com.jondude1.bookshelf.data.Book

/** A titled grid of books: one series, or everything by one author. */
@Composable
fun CollectionScreen(title: String, subtitle: String, books: List<Book>, onBack: () -> Unit, onOpen: (Book) -> Unit, numbered: Boolean) {
    val progress by Graph.progress.all.collectAsState()
    val offline by Graph.files.offline.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(118.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(books.distinctBy { it.id }, key = { it.id }) { b ->
                Column {
                    if (numbered) {
                        val seq = b.series.firstOrNull { it.name.equals(title, true) }?.sequence
                        Text(if (seq != null) "BOOK $seq" else " ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                    BookTile(b, progress[b.id], b.id in offline) { onOpen(b) }
                }
            }
            if (books.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("No books found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SeriesScreen(name: String, onBack: () -> Unit, onOpen: (Book) -> Unit) {
    val books by Graph.library.books.collectAsState()
    val list = remember(books, name) { Graph.library.seriesBooks(name) }
    val progress by Graph.progress.all.collectAsState()
    val read = list.count { progress[it.id]?.finished == true }
    CollectionScreen(name, "${list.size} books" + if (read > 0) " · $read read" else "", list, onBack, onOpen, numbered = true)
}

@Composable
fun AuthorScreen(name: String, onBack: () -> Unit, onOpen: (Book) -> Unit) {
    val books by Graph.library.books.collectAsState()
    val list = remember(books, name) {
        books.filter { b -> b.author.split(",").any { it.trim().equals(name, true) } }
            .sortedWith(compareBy({ it.primarySeries?.name ?: "￿" }, { it.primarySeries?.sequenceNumber ?: 0.0 }, { it.sortTitle }))
    }
    CollectionScreen(name, "${list.size} book${if (list.size == 1) "" else "s"}", list, onBack, onOpen, numbered = false)
}

@Composable
fun SearchScreen(onOpen: (Book) -> Unit, onSeries: (String) -> Unit, onAuthor: (String) -> Unit) {
    val books by Graph.library.books.collectAsState()
    val progress by Graph.progress.all.collectAsState()
    var q by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val terms = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    val results = remember(books, q) {
        if (terms.isEmpty()) emptyList() else books.mapNotNull { b ->
            val hay = listOf(b.title, b.subtitle ?: "", b.author, b.series.joinToString(" ") { it.name }, b.genres.joinToString(" "), b.tags.joinToString(" "))
                .joinToString(" | ").lowercase()
            if (terms.all { hay.contains(it) }) {
                val score = (if (b.title.lowercase().startsWith(terms.first())) 4 else 0) + (if (b.title.lowercase().contains(q.trim().lowercase())) 3 else 0) +
                    (if (b.author.lowercase().contains(q.trim().lowercase())) 2 else 0)
                b to score
            } else null
        }.sortedWith(compareByDescending<Pair<Book, Int>> { it.second }.thenBy { it.first.sortTitle }).map { it.first }
    }
    val seriesHits = remember(books, q) {
        if (terms.isEmpty()) emptyList() else books.flatMap { it.series.map { s -> s.name } }.distinct().filter { n -> terms.all { n.lowercase().contains(it) } }.take(8)
    }
    val authorHits = remember(books, q) {
        if (terms.isEmpty()) emptyList() else books.flatMap { it.author.split(",").map { a -> a.trim() } }.distinct()
            .filter { n -> n.isNotBlank() && terms.all { n.lowercase().contains(it) } }.take(8)
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Default.Close, "Clear") } },
            placeholder = { Text("Titles, authors, series, genres") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 12.dp).focusRequester(focus),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            if (seriesHits.isNotEmpty()) {
                item { Label("Series") }
                items(seriesHits) { s -> ResultLine(s, "${Graph.library.seriesBooks(s).size} books") { onSeries(s) } }
            }
            if (authorHits.isNotEmpty()) {
                item { Label("Authors") }
                items(authorHits) { a -> ResultLine(a, "${books.count { it.author.contains(a) }} books") { onAuthor(a) } }
            }
            if (results.isNotEmpty()) {
                item { Label("Books") }
                items(results.distinctBy { it.id }, key = { it.id }) { b -> BookListRow(b, progress[b.id]?.fraction, progress[b.id]?.finished == true) { onOpen(b) } }
            }
            if (terms.isNotEmpty() && results.isEmpty() && seriesHits.isEmpty() && authorHits.isEmpty()) item {
                Text("Nothing matches “${q.trim()}”.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
private fun ResultLine(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

