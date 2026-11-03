package com.jondude1.bookshelf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jondude1.bookshelf.Graph
import com.jondude1.bookshelf.data.Book
import com.jondude1.bookshelf.data.BookFormat

enum class SortBy(val label: String) { TITLE("Title"), AUTHOR("Author"), ADDED("Recently added"), READ("Recently read"), YEAR("Published") }
enum class Shelf(val label: String) { ALL("All"), READING("Reading"), UNREAD("Unread"), FINISHED("Finished"), OFFLINE("Downloaded") }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (Book) -> Unit, onSeries: (String) -> Unit, onAuthor: (String) -> Unit) {
    val books by Graph.library.books.collectAsState()
    val progress by Graph.progress.all.collectAsState()
    val offline by Graph.files.offline.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    val view by Graph.prefs.reader.collectAsState()
    val sort = SortBy.entries.firstOrNull { it.name == view.librarySort } ?: SortBy.TITLE
    val shelf = Shelf.entries.firstOrNull { it.name == view.libraryShelf } ?: Shelf.ALL
    val format = view.libraryFormat?.let { f -> BookFormat.entries.firstOrNull { it.name == f } }
    val grid = view.libraryGrid
    fun setSort(v: SortBy) = Graph.prefs.updateReader { it.copy(librarySort = v.name) }
    fun setShelf(v: Shelf) = Graph.prefs.updateReader { it.copy(libraryShelf = v.name) }
    fun setFormat(v: BookFormat?) = Graph.prefs.updateReader { it.copy(libraryFormat = v?.name) }
    fun setGrid(v: Boolean) = Graph.prefs.updateReader { it.copy(libraryGrid = v) }
    var sortMenu by remember { mutableStateOf(false) }

    val filtered = remember(books, progress, offline, sort, shelf, format) {
        books.distinctBy { it.id }.filter { b ->
            val p = progress[b.id]
            (format == null || b.format == format) && when (shelf) {
                Shelf.ALL -> true
                Shelf.READING -> p != null && !p.finished && p.fraction > 0.001
                Shelf.UNREAD -> p == null || (!p.finished && p.fraction <= 0.001)
                Shelf.FINISHED -> p?.finished == true
                Shelf.OFFLINE -> b.id in offline
            }
        }.let { list ->
            when (sort) {
                SortBy.TITLE -> list.sortedBy { it.sortTitle }
                SortBy.AUTHOR -> list.sortedWith(compareBy({ it.authorSort }, { it.primarySeries?.name ?: "" }, { it.primarySeries?.sequenceNumber ?: 0.0 }, { it.sortTitle }))
                SortBy.ADDED -> list.sortedByDescending { it.addedAt }
                SortBy.READ -> list.sortedByDescending { progress[it.id]?.updatedAt ?: 0 }
                SortBy.YEAR -> list.sortedByDescending { it.publishedYear?.take(4)?.toIntOrNull() ?: 0 }
            }
        }
    }
    val formats = remember(books) { books.map { it.format }.distinct().filter { it != BookFormat.UNKNOWN }.sortedBy { it.ordinal } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 20.dp, end = 8.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Library", style = MaterialTheme.typography.headlineLarge)
                Text("${filtered.size} of ${books.size} books", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tab == 0) {
                IconButton(onClick = { setGrid(!grid) }) { Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, "Layout") }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        SortBy.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label + if (s == sort) "  ✓" else "") }, onClick = { setSort(s); sortMenu = false })
                        }
                    }
                }
            }
        }
        SecondaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.padding(top = 4.dp)) {
            listOf("Books", "Series", "Authors").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Shelf.entries.forEach { s -> FilterChip(shelf == s, { setShelf(s) }, label = { Text(s.label) }) }
                    if (formats.size > 1) {
                        Spacer(Modifier.width(8.dp))
                        formats.forEach { f -> FilterChip(format == f, { setFormat(if (format == f) null else f) }, label = { Text(f.label) }) }
                    }
                }
                if (grid) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.id }) { b -> BookTile(b, progress[b.id], b.id in offline) { onOpen(b) } }
                        if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                        }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                        items(filtered, key = { it.id }) { b -> BookListRow(b, progress[b.id]?.fraction, progress[b.id]?.finished == true) { onOpen(b) } }
                    }
                }
            }
            1 -> {
                val series = remember(books) {
                    books.flatMap { b -> b.series.map { it.name } }.distinct().sortedBy { it.lowercase().removePrefix("the ") }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    items(series, key = { it }) { name ->
                        val list = Graph.library.seriesBooks(name)
                        Column(Modifier.clickable { onSeries(name) }) {
                            SeriesStack(list)
                            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                            Text("${list.size} book${if (list.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            else -> {
                val authors = remember(books) {
                    books.flatMap { b -> b.author.split(",").map { it.trim() } }.filter { it.isNotBlank() }
                        .groupingBy { it }.eachCount().entries.sortedBy { it.key.split(" ").last().lowercase() }
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(authors, key = { it.key }) { (name, count) ->
                        Row(Modifier.fillMaxWidth().clickable { onAuthor(name) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                                Pill(name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""))
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(Modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun BookListRow(b: Book, fraction: Double?, finished: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().bookActions(b, onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(b, Modifier.width(52.dp), corner = 4.dp, elevation = 3.dp, width = 200)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(b.author.takeIf { it.isNotBlank() }, b.primarySeries?.let { s -> s.name + (s.sequence?.let { " #$it" } ?: "") })
                .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(b.format.label + when {
                finished -> " · Finished"
                fraction != null && fraction > 0.001 -> " · ${(fraction * 100).toInt()}%"
                else -> ""
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
