package com.jondude1.bookshelf.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.serialization.builtins.serializer
import java.io.ByteArrayInputStream
import java.net.URLEncoder

enum class SelectionAction(val label: String) { HIGHLIGHT("Highlight"), NOTE("Note"), COPY("Copy"), DEFINE("Define"), SHARE("Share") }

interface ReaderBridge {
    fun onReady(page: Int, count: Int)
    fun onPage(page: Int, count: Int)
    fun onEdge(direction: Int)
    fun onTap()
    fun onLink(url: String)
    fun onFootnote(text: String, url: String)
    fun onHighlightTap(id: String)
    fun onImage(url: String)
    fun onSelection(action: SelectionAction, json: String)
}

/** WebView that serves the book from memory and swaps the text-selection menu for reading actions. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class BookWebView(context: Context, private val bridge: ReaderBridge) : WebView(context) {
    var book: ReflowBook? = null
    var onLoaded: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    init {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.textZoom = 100
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.mediaPlaybackRequiresUserGesture = true
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isHapticFeedbackEnabled = true
        addJavascriptInterface(JsBridge(), "BSAndroid")
        webViewClient = Client()
    }

    fun chapterUrl(path: String) = "https://$HOST/b/" + path.split('/').joinToString("/") {
        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }

    private inner class JsBridge {
        @JavascriptInterface fun onReady(p: Int, c: Int) = main.post { bridge.onReady(p, c) }.let { }
        @JavascriptInterface fun onPage(p: Int, c: Int) = main.post { bridge.onPage(p, c) }.let { }
        @JavascriptInterface fun onEdge(d: Int) = main.post { bridge.onEdge(d) }.let { }
        @JavascriptInterface fun onTap() = main.post { bridge.onTap() }.let { }
        @JavascriptInterface fun onLink(u: String) = main.post { bridge.onLink(u) }.let { }
        @JavascriptInterface fun onFootnote(t: String, u: String) = main.post { bridge.onFootnote(t, u) }.let { }
        @JavascriptInterface fun onHighlightTap(id: String) = main.post { bridge.onHighlightTap(id) }.let { }
        @JavascriptInterface fun onImage(u: String) = main.post { bridge.onImage(u) }.let { }
        @JavascriptInterface fun log(s: String) = android.util.Log.d("BookShelfJS", s).let { }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

        override fun onPageFinished(view: WebView, url: String?) { onLoaded?.invoke() }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != HOST) return WebResourceResponse("text/plain", "utf-8", 404, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            val path = url.path ?: return notFound()
            return when {
                path.startsWith("/__bs/") -> asset(path.removePrefix("/__bs/"))
                path.startsWith("/b/") -> bookResource(path.removePrefix("/b/"))
                else -> notFound()
            }
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun asset(path: String): WebResourceResponse? = runCatching {
        val stream = context.assets.open(path)
        WebResourceResponse(mimeFor(path), if (path.endsWith(".js")) "utf-8" else null, stream).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400")
        }
    }.getOrElse { notFound() }

    private fun bookResource(path: String): WebResourceResponse {
        val res = book?.resource(path) ?: return notFound()
        if (res.mime.contains("html")) {
            val html = inject(decodeText(res.bytes))
            return WebResourceResponse(res.mime, "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        }
        if (res.mime == "text/css") {
            return WebResourceResponse("text/css", "utf-8", ByteArrayInputStream(res.bytes))
        }
        return WebResourceResponse(res.mime, null, ByteArrayInputStream(res.bytes)).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val enc = Regex("""encoding\s*=\s*["']([\w-]+)["']""").find(head)?.groupValues?.get(1)
            ?: Regex("""charset\s*=\s*["']?([\w-]+)""").find(head)?.groupValues?.get(1)
        val cs = runCatching { enc?.let { charset(it) } }.getOrNull() ?: Charsets.UTF_8
        var s = String(bytes, cs)
        if (s.startsWith("﻿")) s = s.substring(1)
        // We re-encode as UTF-8, so the declaration has to agree
        return s.replaceFirst(Regex("""(<\?xml[^>]*encoding\s*=\s*["'])[\w-]+(["'])"""), "$1utf-8$2")
    }

    private fun inject(html: String): String {
        val tags = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no\"/>" +
            "<style data-bs=\"1\">html.bs-loading body{visibility:hidden}</style>" +
            "<script src=\"/__bs/reader/reader.js\"></script>"
        val headMatch = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
        if (headMatch != null) return html.substring(0, headMatch.range.last + 1) + tags + html.substring(headMatch.range.last + 1)
        val htmlMatch = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
        if (htmlMatch != null) return html.substring(0, htmlMatch.range.last + 1) + "<head>$tags</head>" + html.substring(htmlMatch.range.last + 1)
        return "<!DOCTYPE html><html><head>$tags</head><body>$html</body></html>"
    }

    // ---------- custom selection menu ----------

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(SelectionCallback(callback), type)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(SelectionCallback(callback))

    private inner class SelectionCallback(private val original: ActionMode.Callback?) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear()
            SelectionAction.entries.forEachIndexed { i, a -> menu.add(0, i, i, a.label) }
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = true
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = SelectionAction.entries[item.itemId]
            evaluateJavascript("BS.selection()") { raw ->
                val json = raw?.takeIf { it != "null" }?.let { decodeJsString(it) }
                if (json != null) bridge.onSelection(action, json)
                evaluateJavascript("BS.clearSelection()", null)
            }
            mode.finish()
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) { original?.onDestroyActionMode(mode) }
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (original is ActionMode.Callback2) original.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }

    companion object {
        const val HOST = "book.local"

        /** evaluateJavascript returns JSON-encoded values; strings come back quoted. */
        fun decodeJsString(raw: String): String? = runCatching {
            com.jondude1.bookshelf.data.JsonFmt.decodeFromString(String.serializer(), raw)
        }.getOrNull()
    }
}
