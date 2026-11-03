package com.jondude1.bookshelf.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class AbsException(message: String, val code: Int = 0) : IOException(message)

enum class Route { LAN, REMOTE, OFFLINE }

/**
 * Talks to Audiobookshelf on Athena. Keeps two addresses (home LAN and Tailscale) and
 * quietly moves to whichever one answers, so the app works at home and away.
 */
class AbsClient(private val prefs: Prefs) {

    private val json = "application/json; charset=utf-8".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request()
            val token = prefs.server.value.accessToken
            val known = prefs.server.value.urls.mapNotNull { it.toHttpUrlOrNull()?.host }
            val withAuth = if (token.isNotBlank() && req.header("Authorization") == null && req.url.host in known)
                req.newBuilder().header("Authorization", "Bearer $token").build() else req
            chain.proceed(withAuth)
        }
        .build()

    private val probeClient = http.newBuilder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val _route = MutableStateFlow(Route.LAN)
    val route: StateFlow<Route> = _route

    @Volatile private var activeBase: String? = null
    @Volatile private var checkedAt = 0L
    private val baseLock = Mutex()
    private val refreshLock = Mutex()

    /** The base URL that currently answers, probing both if needed. */
    suspend fun base(force: Boolean = false): String = baseLock.withLock {
        val now = System.currentTimeMillis()
        activeBase?.let { if (!force && now - checkedAt < 90_000) return@withLock it }
        val cfg = prefs.server.value
        val candidates = cfg.urls.let { list -> activeBase?.let { a -> listOf(a) + (list - a) } ?: list }
        for (u in candidates) {
            if (ping(u)) {
                activeBase = u; checkedAt = now
                _route.value = if (u == cfg.lanUrl.trim().trimEnd('/')) Route.LAN else Route.REMOTE
                return@withLock u
            }
        }
        _route.value = Route.OFFLINE
        activeBase = null
        throw AbsException("Can't reach Athena on ${if (cfg.urls.size > 1) "either address" else cfg.urls.firstOrNull() ?: "any address"}")
    }

    /** Best guess of the base without network, for building image URLs. */
    fun cachedBase(): String? = activeBase ?: prefs.server.value.urls.firstOrNull()

    private suspend fun ping(base: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$base/ping".toHttpUrlOrNull() ?: return@withContext false
        runCatching {
            probeClient.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun url(base: String, path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val b = (base + path).toHttpUrlOrNull()?.newBuilder() ?: throw AbsException("Invalid server address: $base")
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build()
    }

    /** Runs a request against the live base, failing over once and refreshing tokens on 401. */
    private suspend fun execute(build: (base: String) -> Request): Response = withContext(Dispatchers.IO) {
        var base = base()
        var resp = try {
            http.newCall(build(base)).execute()
        } catch (e: IOException) {
            base = base(force = true)
            http.newCall(build(base)).execute()
        }
        if (resp.code == 401 && tryRefresh()) {
            resp.close()
            resp = http.newCall(build(base)).execute()
        }
        if (resp.code == 401) {
            resp.close()
            throw AbsException("Athena rejected the login. Sign in again in Settings.", 401)
        }
        resp
    }

    private suspend fun <T> getJson(path: String, ser: KSerializer<T>, query: Map<String, String?> = emptyMap()): T =
        withContext(Dispatchers.IO) {
            execute { b -> Request.Builder().url(url(b, path, query)).build() }.use { r ->
                if (!r.isSuccessful) throw AbsException("Athena returned ${r.code} for $path", r.code)
                JsonFmt.decodeFromString(ser, r.body!!.string())
            }
        }

    private suspend fun tryRefresh(): Boolean = refreshLock.withLock {
        val cfg = prefs.server.value
        if (cfg.usingApiKey || cfg.refreshToken.isBlank()) return@withLock false
        val base = activeBase ?: return@withLock false
        runCatching {
            val req = Request.Builder().url("$base/auth/refresh")
                .header("x-refresh-token", cfg.refreshToken)
                .post("".toRequestBody(json)).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching false
                val u = JsonFmt.decodeFromString(AbsRefreshResponse.serializer(), r.body!!.string()).user
                val access = u.accessToken ?: u.token ?: return@runCatching false
                prefs.updateServer { it.copy(accessToken = access, refreshToken = u.refreshToken ?: it.refreshToken) }
                true
            }
        }.getOrDefault(false)
    }

    // ---------- Auth ----------

    /** Username/password login. Returns the default library id if the server provides one. */
    suspend fun login(lanUrl: String, remoteUrl: String, username: String, password: String): String? =
        withContext(Dispatchers.IO) {
            prefs.updateServer { it.copy(lanUrl = lanUrl.trim().trimEnd('/'), remoteUrl = remoteUrl.trim().trimEnd('/'), accessToken = "") }
            activeBase = null
            val b = base(force = true)
            val body = buildJsonObject { put("username", username); put("password", password) }.toString()
            val req = Request.Builder().url("$b/login")
                .header("x-return-tokens", "true")
                .post(body.toRequestBody(json)).build()
            http.newCall(req).execute().use { r ->
                if (r.code == 401) throw AbsException("Wrong username or password", 401)
                if (!r.isSuccessful) throw AbsException("Login failed (${r.code})", r.code)
                val resp = JsonFmt.decodeFromString(AbsLoginResponse.serializer(), r.body!!.string())
                val access = resp.user.accessToken ?: resp.user.token ?: throw AbsException("Server did not return a token")
                prefs.updateServer {
                    it.copy(accessToken = access, refreshToken = resp.user.refreshToken ?: "", usingApiKey = false,
                        username = resp.user.username, userId = resp.user.id)
                }
                resp.userDefaultLibraryId
            }
        }

    /** API-key login (Audiobookshelf Settings → API Keys). Keys don't expire unless you set them to. */
    suspend fun loginWithKey(lanUrl: String, remoteUrl: String, key: String) = withContext(Dispatchers.IO) {
        prefs.updateServer {
            it.copy(lanUrl = lanUrl.trim().trimEnd('/'), remoteUrl = remoteUrl.trim().trimEnd('/'),
                accessToken = key.trim(), refreshToken = "", usingApiKey = true)
        }
        activeBase = null
        val me = me()
        prefs.updateServer { it.copy(username = me.username, userId = me.id) }
    }

    // ---------- Library ----------

    suspend fun me(): AbsUser = getJson("/api/me", AbsUser.serializer())

    suspend fun libraries(): List<AbsLibrary> = getJson("/api/libraries", AbsLibraries.serializer()).libraries

    /** Every item with an ebook. Asks for everything at once (limit=0); pages only if the server caps it. */
    suspend fun allEbookItems(libraryId: String, onPage: (Int, Int) -> Unit = { _, _ -> }): List<AbsItem> {
        val base = mapOf("minified" to "1", "sort" to "media.metadata.title", "filter" to "ebooks.ZWJvb2s=")
        val first = getJson("/api/libraries/$libraryId/items", AbsItemsPage.serializer(), base + ("limit" to "0"))
        val out = LinkedHashMap<String, AbsItem>()
        first.results.forEach { out[it.id] = it }
        onPage(out.size, first.total)
        if (out.size < first.total) {
            var page = 0
            while (true) {
                val p = getJson("/api/libraries/$libraryId/items", AbsItemsPage.serializer(),
                    base + mapOf("limit" to "200", "page" to page.toString()))
                p.results.forEach { out[it.id] = it }
                onPage(out.size, p.total)
                if (p.results.isEmpty() || out.size >= p.total || page > 500) break
                page++
            }
        }
        return out.values.filter { it.media.ebookFormat != null || it.media.ebookFile != null }
    }

    suspend fun collections(libraryId: String): List<AbsCollection> =
        getJson("/api/libraries/$libraryId/collections", AbsCollectionsPage.serializer(), mapOf("limit" to "0")).results

    suspend fun item(id: String): AbsItem = getJson("/api/items/$id", AbsItem.serializer(), mapOf("expanded" to "1"))

    fun coverUrl(id: String, width: Int = 600): String? =
        cachedBase()?.let { "$it/api/items/$id/cover?width=$width" }

    // ---------- Progress ----------

    suspend fun progress(itemId: String): AbsProgress? = withContext(Dispatchers.IO) {
        execute { b -> Request.Builder().url(url(b, "/api/me/progress/$itemId")).build() }.use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> throw AbsException("Progress fetch failed (${r.code})", r.code)
                else -> JsonFmt.decodeFromString(AbsProgress.serializer(), r.body!!.string())
            }
        }
    }

    suspend fun pushProgress(p: ReadingProgress) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            p.location?.let { put("ebookLocation", it) }
            put("ebookProgress", p.fraction.coerceIn(0.0, 1.0))
            put("isFinished", p.finished)
        }.toString()
        execute { b -> Request.Builder().url(url(b, "/api/me/progress/${p.itemId}")).patch(body.toRequestBody(json)).build() }
            .use { r -> if (!r.isSuccessful) throw AbsException("Progress sync failed (${r.code})", r.code) }
    }

    // ---------- Files ----------

    /** Streams the ebook file to [dest], reporting progress 0..1 (or -1 if unknown). */
    suspend fun downloadEbook(itemId: String, dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        execute { b -> Request.Builder().url(url(b, "/api/items/$itemId/ebook")).build() }.use { r ->
            if (!r.isSuccessful) throw AbsException("Download failed (${r.code})", r.code)
            val body = r.body ?: throw AbsException("Empty download")
            val total = body.contentLength()
            val tmp = File(dest.parentFile, dest.name + ".part")
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (read - lastReport > 256 * 1024) {
                            lastReport = read
                            onProgress(if (total > 0) read.toFloat() / total else -1f)
                        }
                    }
                }
            }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            onProgress(1f)
        }
    }
}
