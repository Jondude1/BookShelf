package com.jondude1.bookshelf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
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
private fun LibraryContent(onOpen: (Book) -> Unit, onSeries: (String) -> Unit, onAuthor: (String) -> Unit, onCollection: (String) -> Unit, selectedId: String?) {
    val collections by Graph.library.collections.collectAsState()
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
            (listOf("Books", "Series", "Authors") + if (collections.isNotEmpty()) listOf("Collections") else emptyList()).forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
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
                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                // First index for each starting letter, when sorted by title
                val letters = remember(filtered, sort) {
                    if (sort != SortBy.TITLE || filtered.size < 40) emptyList()
                    else filtered.mapIndexedNotNull { i, b ->
                        val c = b.sortTitle.firstOrNull()?.uppercaseChar() ?: return@mapIndexedNotNull null
                        (if (c.isLetter()) c else '#') to i
                    }.distinctBy { it.first }
                }
                Box(Modifier.fillMaxSize()) {
                    if (grid) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(112.dp),
                            contentPadding = PaddingValues(start = 20.dp, end = if (letters.isEmpty()) 20.dp else 34.dp, bottom = 32.dp, top = 4.dp),
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
                        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp, end = if (letters.isEmpty()) 0.dp else 14.dp)) {
                            items(filtered, key = { it.id }) { b -> BookListRow(b, progress[b.id]?.fraction, progress[b.id]?.finished == true) { onOpen(b) } }
                        }
                    }
                    if (letters.isNotEmpty()) LetterRail(letters.map { it.first }, Modifier.align(Alignment.CenterEnd)) { idx ->
                        val target = letters[idx].second
                        scope.launch { if (grid) gridState.scrollToItem(target) else listState.scrollToItem(target) }
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
            3 -> {
                val byId = remember(books) { books.associateBy { it.id } }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    items(collections, key = { it.id }) { c ->
                        val list = c.bookIds.mapNotNull { byId[it] }
                        Column(Modifier.clickable { onCollection(c.id) }) {
                            SeriesStack(list)
                            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
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

/** On wide screens (tablet landscape) the library and a book's details sit side by side. */
@Composable
fun LibraryScreen(onOpen: (Book) -> Unit, onSeries: (String) -> Unit, onAuthor: (String) -> Unit, onCollection: (String) -> Unit = {}) {
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    if (!wide) { LibraryContent(onOpen, onSeries, onAuthor, onCollection, null); return }
    val read = LocalReadBook.current
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { LibraryContent({ selected = it.id }, onSeries, onAuthor, onCollection, selected) }
        androidx.compose.animation.AnimatedVisibility(selected != null,
            enter = androidx.compose.animation.slideInHorizontally { it } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally { it } + androidx.compose.animation.fadeOut()) {
            Row {
                androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.width(460.dp).fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    selected?.let { id ->
                        androidx.compose.runtime.key(id) {
                            BookDetailScreen(id, onBack = { selected = null }, onRead = { read(it.id) }, onOpen = { selected = it.id },
                                onSeries = onSeries, onAuthor = onAuthor, compact = true)
                        }
                    }
                }
            }
        }
    }
}

/** Vertical A–Z strip: tap or drag to jump through a title-sorted list. */
@Composable
private fun LetterRail(letters: List<Char>, modifier: Modifier, onPick: (Int) -> Unit) {
    var active by remember { mutableStateOf<Int?>(null) }
    var height by remember { mutableStateOf(1) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun pick(y: Float) {
        val i = ((y / height) * letters.size).toInt().coerceIn(0, letters.lastIndex)
        if (i != active) { active = i; onPick(i); haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
    }
    Box(modifier.padding(vertical = 12.dp)) {
        Column(
            Modifier.width(28.dp)
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .pointerInput(letters) {
                    detectTapGestures(onPress = { o -> pick(o.y); tryAwaitRelease(); active = null })
                }
                .pointerInput(letters) {
                    detectVerticalDragGestures(onDragEnd = { active = null }, onDragCancel = { active = null }) { change, _ -> pick(change.position.y) }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            letters.forEachIndexed { i, c ->
                Text(c.toString(), style = MaterialTheme.typography.labelSmall,
                    color = if (active == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        active?.let { i ->
            Box(Modifier.align(Alignment.CenterStart).offset(x = (-64).dp).size(52.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Text(letters[i].toString(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
