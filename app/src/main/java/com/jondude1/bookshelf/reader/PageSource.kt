package com.jondude1.bookshelf.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** A fixed-layout book: PDF pages or comic images. */
interface PageSource : Closeable {
    val pageCount: Int
    /** Width/height of a page, for layout before it renders. */
    fun aspect(index: Int): Float
    suspend fun render(index: Int, targetWidth: Int): Bitmap?
    /** Text for in-book search (PDF only; comics have none). */
    fun pageText(index: Int): String? = null
}

class PdfSource(file: File) : PageSource {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(fd) } catch (e: SecurityException) {
        fd.close(); throw IllegalStateException("This PDF is password-protected")
    }
    private val lock = Mutex()
    private val aspects = HashMap<Int, Float>()
    override val pageCount = renderer.pageCount

    override fun aspect(index: Int): Float = aspects[index] ?: synchronized(renderer) {
        aspects.getOrPut(index) {
            runCatching { renderer.openPage(index).use { it.width.toFloat() / it.height } }.getOrDefault(0.707f)
        }
    }

    override suspend fun render(index: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            synchronized(renderer) {
                runCatching {
                    renderer.openPage(index).use { page ->
                        val w = targetWidth.coerceIn(200, 2600)
                        val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        aspects[index] = page.width.toFloat() / page.height
                        bmp
                    }
                }.getOrNull()
            }
        }
    }

    override fun pageText(index: Int): String? {
        if (android.os.Build.VERSION.SDK_INT < 35) return null
        return synchronized(renderer) {
            runCatching { renderer.openPage(index).use { p -> p.textContents.joinToString(" ") { it.text } } }.getOrNull()
        }
    }

    override fun close() { runCatching { renderer.close() }; runCatching { fd.close() } }
}

private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
private val naturalOrder = Comparator<String> { a, b ->
    val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
    val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(ra.size, rb.size)) {
        val x = ra[i]; val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) (x.toBigIntegerOrNull() ?: 0.toBigInteger()).compareTo(y.toBigIntegerOrNull() ?: 0.toBigInteger()) else x.compareTo(y)
        if (c != 0) return@Comparator c
    }
    ra.size - rb.size
}

private fun decodeScaled(bytesOrFile: () -> ByteArray, targetWidth: Int): Bitmap? {
    val bytes = bytesOrFile()
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var sample = 1
    while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

class CbzSource(file: File) : PageSource {
    private val zip = ZipFile(file)
    private val names = zip.entries().asSequence().filter { !it.isDirectory }
        .map { it.name }
        .filter { it.substringAfterLast('.', "").lowercase() in imageExt && !it.substringAfterLast('/').startsWith(".") && !it.startsWith("__MACOSX") }
        .sortedWith(naturalOrder).toList()
    private val aspects = HashMap<Int, Float>()
    override val pageCount = names.size.also { if (it == 0) throw IllegalStateException("No images found in this comic") }

    override fun aspect(index: Int) = aspects[index] ?: 0.66f

    override suspend fun render(index: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = synchronized(zip) { zip.getInputStream(zip.getEntry(names[index])).use { it.readBytes() } }
            decodeScaled({ bytes }, targetWidth)?.also { aspects[index] = it.width.toFloat() / it.height }
        }.getOrNull()
    }

    override fun close() = zip.close()
}

/** CBR: extracted once into a cache folder, then read like loose images. */
class CbrSource(file: File, cacheRoot: File) : PageSource {
    private val dir = File(cacheRoot, "cbr-" + file.nameWithoutExtension).apply { mkdirs() }
    private val pages: List<File>
    private val aspects = HashMap<Int, Float>()

    init {
        val done = File(dir, ".done")
        if (!done.exists()) {
            Archive(file).use { arc ->
                var n = 0
                for (h in arc.fileHeaders) {
                    if (h.isDirectory) continue
                    val name = h.fileName.replace('\\', '/')
                    if (name.substringAfterLast('.', "").lowercase() !in imageExt) continue
                    val safe = name.replace('/', '_')
                    File(dir, safe).outputStream().use { arc.extractFile(h, it) }
                    n++
                }
                if (n == 0) throw IllegalStateException("No images found in this comic")
            }
            done.writeText("ok")
        }
        pages = dir.listFiles().orEmpty().filter { it.extension.lowercase() in imageExt }.sortedWith { a, b -> naturalOrder.compare(a.name, b.name) }
    }

    override val pageCount get() = pages.size
    override fun aspect(index: Int) = aspects[index] ?: 0.66f

    override suspend fun render(index: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { decodeScaled({ pages[index].readBytes() }, targetWidth)?.also { aspects[index] = it.width.toFloat() / it.height } }.getOrNull()
    }

    override fun close() {}
}
