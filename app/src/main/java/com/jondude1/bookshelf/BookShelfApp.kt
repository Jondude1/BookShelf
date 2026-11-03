package com.jondude1.bookshelf

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.jondude1.bookshelf.data.AbsClient
import com.jondude1.bookshelf.data.AnnotationRepository
import com.jondude1.bookshelf.data.FileRepository
import com.jondude1.bookshelf.data.LibraryRepository
import com.jondude1.bookshelf.data.Prefs
import com.jondude1.bookshelf.data.ProgressRepository
import com.jondude1.bookshelf.data.StatsRepository
import com.jondude1.bookshelf.update.Updater
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

open class BookShelfApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        Graph.init(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Graph.client.http)
        .respectCacheHeaders(false)
        .crossfade(220)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.22).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(400L * 1024 * 1024).build() }
        .build()
}

/** Simple service locator — one of each, app-wide. */
object Graph {
    lateinit var app: Application; private set
    /** Background work never takes the app down; failures are logged instead. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        android.util.Log.e("BookShelf", "Background task failed", e)
        CrashReporter.record(e, fatal = false)
    })
    lateinit var prefs: Prefs; private set
    lateinit var client: AbsClient; private set
    lateinit var library: LibraryRepository; private set
    lateinit var progress: ProgressRepository; private set
    lateinit var files: FileRepository; private set
    lateinit var annotations: AnnotationRepository; private set
    lateinit var stats: StatsRepository; private set
    lateinit var updater: Updater; private set

    fun init(app: Application) {
        this.app = app
        prefs = Prefs(app, scope)
        client = AbsClient(prefs)
        library = LibraryRepository(app, client, prefs, scope)
        progress = ProgressRepository(app, client, scope)
        files = FileRepository(app, client, scope)
        annotations = AnnotationRepository(app)
        stats = StatsRepository(app, scope)
        updater = Updater(app, scope)
    }
}

/** Saves the last crash so the next launch can show what went wrong. */
object CrashReporter {
    private var file: java.io.File? = null

    fun install(app: Application) {
        file = java.io.File(app.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            record(e, fatal = true)
            previous?.uncaughtException(t, e)
        }
    }

    fun record(e: Throwable, fatal: Boolean) {
        if (!fatal) return
        runCatching {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            file?.writeText("BookShelf ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n\n$sw")
        }
    }

    /** Returns and clears the saved report, if any. */
    fun takeReport(): String? = file?.takeIf { it.exists() }?.let { f -> runCatching { f.readText() }.getOrNull().also { f.delete() } }
}
