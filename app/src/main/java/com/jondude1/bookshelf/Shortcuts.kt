package com.jondude1.bookshelf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.jondude1.bookshelf.data.Book

/** Launcher long-press shortcut that reopens the book you're reading. */
object Shortcuts {
    const val EXTRA_READ = "com.jondude1.bookshelf.READ"

    suspend fun publishContinue(context: Context, book: Book) {
        runCatching {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_READ, book.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val icon = coverIcon(context, book) ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
            val info = ShortcutInfoCompat.Builder(context, "continue")
                .setShortLabel(book.title.take(24))
                .setLongLabel("Continue: ${book.title}".take(48))
                .setIcon(icon)
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, info)
        }
    }

    private suspend fun coverIcon(context: Context, book: Book): IconCompat? {
        val req = ImageRequest.Builder(context)
            .data(Graph.client.coverUrl(book.id, 300))
            .memoryCacheKey("cover-${book.id}-300").diskCacheKey("cover-${book.id}-300")
            .allowHardware(false).build()
        val result = context.imageLoader.execute(req) as? SuccessResult ?: return null
        val src = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
        // Adaptive icons crop to the centre ~66%, so letterbox the cover on a dark square
        val size = 432
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF14161A.toInt())
        val h = (size * 0.62f).toInt(); val w = (h * src.width.toFloat() / src.height).toInt()
        val scaled = Bitmap.createScaledBitmap(src, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
        c.drawBitmap(scaled, (size - w) / 2f, (size - h) / 2f, null)
        return IconCompat.createWithAdaptiveBitmap(out)
    }
}
