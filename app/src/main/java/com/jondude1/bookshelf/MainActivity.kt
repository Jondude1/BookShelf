package com.jondude1.bookshelf

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jondude1.bookshelf.reader.ReaderScreen
import com.jondude1.bookshelf.ui.AuthorScreen
import com.jondude1.bookshelf.ui.BookDetailScreen
import com.jondude1.bookshelf.ui.BookShelfTheme
import com.jondude1.bookshelf.ui.HomeScreen
import com.jondude1.bookshelf.ui.LibraryScreen
import com.jondude1.bookshelf.ui.LoginScreen
import com.jondude1.bookshelf.ui.SearchScreen
import com.jondude1.bookshelf.ui.SeriesScreen
import com.jondude1.bookshelf.ui.SettingsScreen
import com.jondude1.bookshelf.ui.StatsScreen
import com.jondude1.bookshelf.update.UpdateState
import kotlinx.coroutines.launch
import android.net.Uri

class MainActivity : ComponentActivity() {
    companion object {
        /** Set by the open reader; returns true if it used the key. */
        var volumeHandler: ((forward: Boolean) -> Boolean)? = null
        /** Book to open straight away, from the launcher shortcut. */
        val pendingRead = androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) {
                Graph.updater.checkQuietly()
                if (Graph.prefs.server.value.isLoggedIn) Graph.scope.launch { Graph.progress.syncFromServer() }
            }
            if (e == Lifecycle.Event.ON_STOP) Graph.progress.flush()
        })
        pendingRead.value = intent?.getStringExtra(Shortcuts.EXTRA_READ)
        setContent { BookShelfTheme { App() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Shortcuts.EXTRA_READ)?.let { pendingRead.value = it }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val h = volumeHandler
        if (h != null && (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (h(event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
            } else return true
        }
        return super.dispatchKeyEvent(event)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Tab("library", "Library", Icons.AutoMirrored.Outlined.LibraryBooks, Icons.AutoMirrored.Filled.LibraryBooks),
    Tab("search", "Search", Icons.Outlined.Search, Icons.Filled.Search),
    Tab("stats", "Stats", Icons.Outlined.BarChart, Icons.Filled.BarChart),
    Tab("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

private fun enc(s: String) = Uri.encode(s)

@Composable
private fun CrashDialog() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var report by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CrashReporter.takeReport()) }
    val r = report ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { report = null },
        title = { Text("BookShelf closed unexpectedly") },
        text = {
            Column {
                Text("Share this report so it can be fixed.", style = MaterialTheme.typography.bodyMedium)
                Text(r.lineSequence().take(14).joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, r)
                context.startActivity(android.content.Intent.createChooser(i, "Share crash report"))
                report = null
            }) { Text("Share report") }
        },
        dismissButton = { TextButton(onClick = { report = null }) { Text("Close") } },
    )
}

@Composable
private fun App() {
    CrashDialog()
    val nav = rememberNavController()
    val server by Graph.prefs.server.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: ""
    val showBars = tabs.any { it.route == route }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val start = if (server.isLoggedIn) "home" else "login"

    fun openBook(id: String) = nav.navigate("book/$id")
    val pending = MainActivity.pendingRead.value
    androidx.compose.runtime.LaunchedEffect(pending, server.isLoggedIn) {
        if (pending != null && server.isLoggedIn) { MainActivity.pendingRead.value = null; nav.navigate("read/$pending") }
    }
    fun read(id: String) = nav.navigate("read/$id")
    fun series(name: String) = nav.navigate("series/${enc(name)}")
    fun author(name: String) = nav.navigate("author/${enc(name)}")

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (showBars && wide) NavigationRail(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                Spacer(Modifier.padding(top = 12.dp))
                tabs.forEach { t ->
                    NavigationRailItem(selected = route == t.route, onClick = { switchTab(nav, t.route) },
                        icon = { Icon(if (route == t.route) t.selectedIcon else t.icon, t.label) }, label = { Text(t.label) })
                }
            }
            Column(Modifier.weight(1f)) {
                if (showBars) UpdateBanner()
                Box(Modifier.weight(1f)) {
                    androidx.compose.runtime.CompositionLocalProvider(com.jondude1.bookshelf.ui.LocalReadBook provides { id: String -> nav.navigate("read/$id") }) {
                    NavHost(nav, startDestination = start) {
                        composable("login") {
                            LoginScreen(onDone = { nav.navigate("home") { popUpTo("login") { inclusive = true } } })
                        }
                        composable("home") {
                            HomeScreen(onOpen = { openBook(it.id) }, onRead = { read(it.id) }, onSeries = ::series,
                                onLibrary = { switchTab(nav, "library") }, onStats = { switchTab(nav, "stats") },
                                onCollection = { nav.navigate("collection/$it") }, onNotes = { nav.navigate("notes") })
                        }
                        composable("library") { LibraryScreen(onOpen = { openBook(it.id) }, onSeries = ::series, onAuthor = ::author, onCollection = { nav.navigate("collection/$it") }) }
                        composable("collection/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                            com.jondude1.bookshelf.ui.BookCollectionScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }, onOpen = { openBook(it.id) })
                        }
                        composable("search") { SearchScreen(onOpen = { openBook(it.id) }, onSeries = ::series, onAuthor = ::author) }
                        composable("stats") { StatsScreen() }
                        composable("notes") { com.jondude1.bookshelf.ui.NotesScreen(onBack = { nav.popBackStack() }, onRead = { read(it.id) }, onOpen = { openBook(it.id) }) }
                        composable("settings") {
                            SettingsScreen(onSignedOut = { nav.navigate("login") { popUpTo(0) { inclusive = true } } }, onOpenBook = ::openBook)
                        }
                        composable("book/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                            BookDetailScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }, onRead = { read(it.id) },
                                onOpen = { openBook(it.id) }, onSeries = ::series, onAuthor = ::author)
                        }
                        composable("series/{name}", arguments = listOf(navArgument("name") { type = NavType.StringType })) { e ->
                            SeriesScreen(Uri.decode(e.arguments!!.getString("name")!!), onBack = { nav.popBackStack() }, onOpen = { openBook(it.id) })
                        }
                        composable("author/{name}", arguments = listOf(navArgument("name") { type = NavType.StringType })) { e ->
                            AuthorScreen(Uri.decode(e.arguments!!.getString("name")!!), onBack = { nav.popBackStack() }, onOpen = { openBook(it.id) })
                        }
                        composable("read/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                            ReaderScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() },
                                onOpenBook = { id -> nav.navigate("read/$id") { popUpTo("read/{id}") { inclusive = true } } })
                        }
                    }
                    }
                }
                if (showBars && !wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route, onClick = { switchTab(nav, t.route) },
                            icon = { Icon(if (route == t.route) t.selectedIcon else t.icon, t.label) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            }
        }
    }
}

private fun switchTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun UpdateBanner() {
    val state by Graph.updater.state.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val available = state as? UpdateState.Available
    val downloading = state as? UpdateState.Downloading
    val visible = (available != null && available.update.tag != Graph.updater.dismissedTag) || downloading != null
    AnimatedVisibility(visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (downloading != null) {
                Text("Downloading update…", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                LinearProgressIndicator(progress = { downloading.progress }, modifier = Modifier.width(90.dp).padding(end = 12.dp))
            } else if (available != null) {
                Text("Update available — ${available.update.tag}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
                TextButton(onClick = { Graph.updater.dismissedTag = available.update.tag }) { Text("Later") }
                TextButton(onClick = { Graph.updater.downloadAndInstall(activity, available.update) }) { Text("Update") }
            }
        }
    }
}
