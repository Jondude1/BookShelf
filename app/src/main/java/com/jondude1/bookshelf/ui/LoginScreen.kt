package com.jondude1.bookshelf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jondude1.bookshelf.Graph
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val cfg = Graph.prefs.server.value
    var lan by remember { mutableStateOf(cfg.lanUrl.ifBlank { "http://" }) }
    var remote by remember { mutableStateOf(cfg.remoteUrl) }
    var mode by remember { mutableStateOf(0) }
    var user by remember { mutableStateOf(cfg.username) }
    var pass by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun normalize(u: String): String {
        val t = u.trim().trimEnd('/')
        if (t.isBlank() || t == "http:" || t == "http://") return ""
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
    }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f), MaterialTheme.colorScheme.background)))
            .safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(14.dp))
            Text("BookShelf", style = MaterialTheme.typography.displayMedium)
            Text("Your library on Athena, beautifully read.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(36.dp))

            OutlinedTextField(lan, { lan = it }, label = { Text("Home address") }, placeholder = { Text("http://192.168.1.20:13378") },
                supportingText = { Text("Audiobookshelf on your home network") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(remote, { remote = it }, label = { Text("Away address (optional)") }, placeholder = { Text("http://athena:13378") },
                supportingText = { Text("Your Tailscale name or IP — used automatically when home isn't reachable") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Username", "API key").forEachIndexed { i, l ->
                    SegmentedButton(selected = mode == i, onClick = { mode = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (mode == 0) {
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true,
                    supportingText = { Text("Create one in Audiobookshelf → Settings → API Keys") }, modifier = Modifier.fillMaxWidth())
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    error = null
                    val l = normalize(lan); val r = normalize(remote)
                    if (l.isBlank() && r.isBlank()) { error = "Enter at least one address for Athena"; return@Button }
                    busy = true
                    scope.launch {
                        runCatching {
                            if (mode == 0) Graph.client.login(l, r, user.trim(), pass)
                            else Graph.client.loginWithKey(l, r, key)
                            Graph.library.refresh().getOrThrow()
                            Graph.progress.syncFromServer()
                        }.onSuccess { onDone() }
                            .onFailure { error = it.message ?: "Couldn't sign in" }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Connect to Athena")
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                Text("Reads EPUB, PDF, MOBI, AZW3, CBZ and CBR from your Audiobookshelf ebook libraries.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
