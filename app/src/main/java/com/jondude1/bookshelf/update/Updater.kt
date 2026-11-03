package com.jondude1.bookshelf.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.jondude1.bookshelf.BuildConfig
import com.jondude1.bookshelf.data.JsonFmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
private data class GhRelease(val tag_name: String = "", val name: String? = null, val body: String? = null, val assets: List<GhAsset> = emptyList())

@Serializable
private data class GhAsset(val name: String = "", val browser_download_url: String = "", val size: Long = 0)

data class AvailableUpdate(val versionCode: Int, val tag: String, val url: String, val notes: String?)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Checks Jondude1/BookShelf releases and installs newer APKs in place. */
class Updater(private val context: Context, private val scope: CoroutineScope) {
    private val repo = "Jondude1/BookShelf"
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var lastCheck = 0L
    var dismissedTag by androidx.compose.runtime.mutableStateOf<String?>(null)

    val installedVersion get() = BuildConfig.VERSION_NAME

    fun checkQuietly() {
        if (System.currentTimeMillis() - lastCheck < 30 * 60_000) return
        check(manual = false)
    }

    fun check(manual: Boolean = true) {
        if (_state.value is UpdateState.Downloading) return
        lastCheck = System.currentTimeMillis()
        scope.launch {
            if (manual) _state.value = UpdateState.Checking
            val result = runCatching { fetchLatest() }
            _state.value = result.fold(
                onSuccess = { u -> if (u != null && u.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(u) else UpdateState.UpToDate },
                onFailure = { if (manual) UpdateState.Failed(it.message ?: "Couldn't check for updates") else UpdateState.Idle },
            )
        }
    }

    private suspend fun fetchLatest(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        http.newBuilder().followRedirects(true).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("GitHub returned ${r.code}")
            val rel = JsonFmt.decodeFromString(GhRelease.serializer(), r.body!!.string())
            val code = Regex("""(\d+)\s*$""").find(rel.tag_name)?.groupValues?.get(1)?.toIntOrNull() ?: return@use null
            val apk = rel.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@use null
            AvailableUpdate(code, rel.tag_name, apk.browser_download_url, rel.body)
        }
    }

    fun downloadAndInstall(activity: Activity, update: AvailableUpdate) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        scope.launch {
            _state.value = UpdateState.Downloading(update, 0f)
            val result = runCatching { download(update) { p -> _state.value = UpdateState.Downloading(update, p) } }
            result.onSuccess { file ->
                _state.value = UpdateState.Available(update)
                withContext(Dispatchers.Main) { install(activity, file) }
            }.onFailure { _state.value = UpdateState.Failed(it.message ?: "Download failed") }
        }
    }

    private suspend fun download(update: AvailableUpdate, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "BookShelf-${update.tag}.apk")
        var url = update.url
        repeat(6) {
            val resp = http.newCall(Request.Builder().url(url).build()).execute()
            if (resp.isRedirect) {
                url = resp.header("Location") ?: throw IllegalStateException("Bad redirect")
                resp.close()
                return@repeat
            }
            resp.use { r ->
                if (!r.isSuccessful) throw IllegalStateException("Download failed (${r.code})")
                val body = r.body!!
                val total = body.contentLength()
                body.byteStream().use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            o.write(buf, 0, n); read += n
                            if (total > 0) onProgress(read.toFloat() / total)
                        }
                    }
                }
            }
            return@withContext out
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun install(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
