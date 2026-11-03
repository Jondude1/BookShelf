package com.jondude1.bookshelf.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jondude1.bookshelf.Graph
import com.jondude1.bookshelf.data.AbsLibrary
import com.jondude1.bookshelf.data.ReaderTheme
import com.jondude1.bookshelf.reader.ReaderColors
import com.jondude1.bookshelf.reader.ReaderSettingsPanel
import com.jondude1.bookshelf.update.UpdateState
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onSignedOut: () -> Unit, onOpenBook: (String) -> Unit) {
    val context = LocalContext.current
    val server by Graph.prefs.server.collectAsState()
    val reader by Graph.prefs.reader.collectAsState()
    val route by Graph.client.route.collectAsState()
    val books by Graph.library.books.collectAsState()
    val offline by Graph.files.offline.collectAsState()
    val update by Graph.updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    var editServer by remember { mutableStateOf(false) }
    var showReading by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var libraries by remember { mutableStateOf<List<AbsLibrary>>(emptyList()) }
    var cacheSize by remember { mutableLongStateOf(Graph.files.cacheSize()) }
    var testResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { libraries = runCatching { Graph.client.libraries().filter { it.mediaType == "book" } }.getOrDefault(emptyList()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.statusBars).padding(bottom = 32.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(20.dp))

        Card("Athena") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(server.username.ifBlank { "Signed in" } + if (server.usingApiKey) " · API key" else "", style = MaterialTheme.typography.titleSmall)
                    Text("Home: ${server.lanUrl.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Away: ${server.remoteUrl.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ConnectionChip(route)
            }
            val lastSync by Graph.progress.lastSync.collectAsState()
            val pending = Graph.progress.all.collectAsState().value.values.count { it.dirty }
            Text(
                (if (lastSync > 0) "Progress synced " + android.text.format.DateUtils.getRelativeTimeSpanString(lastSync) else "Progress not synced yet this session") +
                    if (pending > 0) " · $pending waiting to upload" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    testResult = "Testing…"
                    scope.launch { testResult = runCatching { Graph.client.base(force = true) }.fold({ "Connected via $it" }, { it.message }) }
                }) { Text("Test connection") }
                OutlinedButton(onClick = { editServer = true }) { Text("Edit addresses") }
            }
            if (libraries.size > 1) {
                Text("Libraries", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                (listOf(AbsLibrary("", "All ebook libraries")) + libraries).forEach { lib ->
                    Row(Modifier.fillMaxWidth().clickable {
                        Graph.prefs.updateServer { it.copy(libraryId = lib.id) }
                        scope.launch { Graph.library.refresh() }
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(lib.name, modifier = Modifier.weight(1f))
                        if (server.libraryId == lib.id) Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        Card("Reading") {
            SettingRow("Reading display & controls", "Theme, fonts, spacing, night light, page turns") { showReading = true }
            ToggleSetting("Keep books I'm reading offline", "Your 5 most recent in-progress books, downloaded on Wi-Fi", reader.keepReadingOffline) { v -> Graph.prefs.updateReader { it.copy(keepReadingOffline = v) } }
            ToggleSetting("Download the next book in a series", "When you're near the end of a book", reader.autoDownloadNext) { v -> Graph.prefs.updateReader { it.copy(autoDownloadNext = v) } }
            ToggleSetting("Automatic night theme", "${reader.nightTheme.label} from ${reader.nightStartHour}:00 to ${reader.nightEndHour}:00", reader.autoNight) { v -> Graph.prefs.updateReader { it.copy(autoNight = v) } }
            if (reader.autoNight) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                ReaderTheme.entries.filter { it.dark }.forEach { t ->
                    androidx.compose.material3.FilterChip(reader.nightTheme == t, { Graph.prefs.updateReader { it.copy(nightTheme = t) } }, label = { Text(t.label) })
                }
            }
        }

        Card("Downloads") {
            val downloaded = books.filter { it.id in offline }
            Text("${downloaded.size} books on this device · ${formatBytes(Graph.files.offlineSize())}", style = MaterialTheme.typography.bodyMedium)
            downloaded.forEach { b ->
                Row(Modifier.fillMaxWidth().clickable { onOpenBook(b.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(b, Modifier.width(34.dp), corner = 3.dp, elevation = 2.dp, width = 120)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(formatBytes(Graph.files.offlineFile(b.id)?.length() ?: 0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { Graph.files.remove(b.id) }) { Icon(Icons.Default.DeleteOutline, "Remove") }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Recently opened cache", style = MaterialTheme.typography.bodyMedium)
                    Text(formatBytes(cacheSize) + " · trimmed automatically", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { Graph.files.clearCache(); cacheSize = Graph.files.cacheSize() }) { Text("Clear") }
            }
        }

        Card("App") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("BookShelf ${Graph.updater.installedVersion}", style = MaterialTheme.typography.titleSmall)
                    Text(when (val u = update) {
                        UpdateState.Checking -> "Checking…"
                        UpdateState.UpToDate -> "You're on the latest version"
                        is UpdateState.Available -> "Version ${u.update.tag.removePrefix("v")} is ready"
                        is UpdateState.Downloading -> "Downloading ${(u.progress * 100).toInt()}%"
                        is UpdateState.Failed -> u.message
                        else -> "Updates install from GitHub releases"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val u = update
                if (u is UpdateState.Available) Button(onClick = { Graph.updater.downloadAndInstall(context as Activity, u.update) }) { Text("Update") }
                else OutlinedButton(onClick = { Graph.updater.check(manual = true) }) { Text("Check") }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = { confirmSignOut = true }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (editServer) {
        var lan by remember { mutableStateOf(server.lanUrl) }
        var remote by remember { mutableStateOf(server.remoteUrl) }
        AlertDialog(
            onDismissRequest = { editServer = false },
            title = { Text("Athena addresses") },
            text = {
                Column {
                    OutlinedTextField(lan, { lan = it }, label = { Text("Home address") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(remote, { remote = it }, label = { Text("Away address (Tailscale)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Graph.prefs.updateServer { it.copy(lanUrl = lan.trim().trimEnd('/'), remoteUrl = remote.trim().trimEnd('/')) }
                    scope.launch { runCatching { Graph.client.base(force = true) } }
                    editServer = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editServer = false }) { Text("Cancel") } },
        )
    }

    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out of Athena?") },
        text = { Text("Downloads, highlights and reading stats stay on this device.") },
        confirmButton = { TextButton(onClick = {
            Graph.progress.flush()
            Graph.prefs.updateServer { it.copy(accessToken = "", refreshToken = "") }
            confirmSignOut = false
            onSignedOut()
        }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
    )

    if (showReading) {
        val theme = reader.theme
        val colors = ReaderColors.of(theme)
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showReading = false }, containerColor = colors.bg, contentColor = colors.fg) {
            ReaderSettingsPanel(reader, colors, reflow = true, isPdf = true, isComic = true) { f -> Graph.prefs.updateReader(f) }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp)) { content() }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleSetting(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}
