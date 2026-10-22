package io.notepit.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.RandomAccessFile

private const val ENGINE_ORIGIN = "https://appassets.androidplatform.net"

/** Restricts the privileged bridge to the main local engine frame, never document content. */
@SuppressLint("SetJavaScriptEnabled")
class DocumentWebView(context: Context, val session: OpenDocument, private val sessions: DocumentSessions, dark: Boolean) {
    val view = WebView(context.applicationContext)
    val generation = session.reload
    private var darkMode = dark
    private var pendingTransfer: JSONObject? = null
    private val checkpoints = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var nextCheckpoint = 0
    private var destroyed = false
    var ready by mutableStateOf(false)
        private set

    init {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) {
            "Update Android System WebView to open documents. Binary messaging support is required."
        }
        view.setBackgroundColor(if (dark) Color.rgb(17, 20, 31) else Color.rgb(250, 250, 255))
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context.applicationContext)).build()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https" || request.url.host != "appassets.androidplatform.net"

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                if (url.scheme != "https" || url.host != "appassets.androidplatform.net") return response(403, "Forbidden", "text/plain", ByteArray(0))
                val path = url.path.orEmpty()
                return try {
                    when (path) {
                        "/session/${session.id}/config" -> response(200, "OK", "application/json", sessions.config(session, darkMode).toString().toByteArray())
                        "/session/${session.id}/document" -> documentResponse(request)
                        else -> {
                            if (path.startsWith("/assets/") && (path.endsWith(".mjs") || path.endsWith(".wasm") || path.endsWith(".ftl"))) {
                                WebResourceResponse(if (path.endsWith(".wasm")) "application/wasm" else if (path.endsWith(".ftl")) "text/plain" else "text/javascript", "UTF-8", context.assets.open(path.removePrefix("/assets/")))
                            } else assets.shouldInterceptRequest(url) ?: response(404, "Not Found", "text/plain", ByteArray(0))
                        }
                    }
                } catch (error: Exception) {
                    response(503, "Unavailable", "text/plain", (error.message ?: "Document unavailable").toByteArray())
                }
            }
        }
        view.webChromeClient = WebChromeClient()
        WebViewCompat.addWebMessageListener(view, "NotePitNative", setOf(ENGINE_ORIGIN)) { _, message, sourceOrigin, mainFrame, _ ->
            if (!mainFrame || sourceOrigin.toString() != ENGINE_ORIGIN || destroyed) return@addWebMessageListener
            try {
                if (message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
                    val header = pendingTransfer ?: error("Unexpected document data.")
                    pendingTransfer = null
                    sessions.receive(session, header, message.arrayBuffer) { success, error ->
                        val purpose = header.getString("purpose")
                        evaluate("window.NotePit?.acknowledge(${JSONObject.quote(purpose)}, ${header.getLong("seq")}, $success, ${JSONObject.quote(error ?: "")})")
                        completeCheckpoint(header.optString("request"), if (success) null else IllegalStateException(error))
                    }
                } else {
                    val event = JSONObject(message.data ?: "{}")
                    when (event.optString("type")) {
                        "ready" -> { ready = true; session.error = null }
                        "transfer" -> { check(pendingTransfer == null) { "A document transfer is already pending." }; pendingTransfer = event }
                        "checkpoint" -> sessions.checkpoint(session) {
                            completeCheckpoint(event.optString("request"), event.optString("error").takeIf { it.isNotBlank() }?.let { IllegalStateException(it) })
                        }
                        else -> sessions.accept(session, event)
                    }
                }
            } catch (error: Exception) { session.error = error.message; session.busy = false }
        }
        view.loadUrl("$ENGINE_ORIGIN/assets/index.html?session=${session.id}")
    }

    private fun response(status: Int, reason: String, mime: String, bytes: ByteArray) = WebResourceResponse(
        mime, "UTF-8", status, reason, mapOf("Cache-Control" to "no-store", "Content-Length" to bytes.size.toString()), ByteArrayInputStream(bytes),
    )

    private fun documentResponse(request: WebResourceRequest): WebResourceResponse {
        val file = session.current
        val length = file.length()
        val range = request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
        var start = 0L
        var end = length - 1
        if (range != null) {
            if (!range.startsWith("bytes=") || range.contains(',')) return response(416, "Range Not Satisfiable", "text/plain", ByteArray(0))
            val parts = range.removePrefix("bytes=").split('-', limit = 2)
            if (parts[0].isBlank()) start = (length - (parts.getOrNull(1)?.toLongOrNull() ?: 0)).coerceAtLeast(0)
            else start = parts[0].toLongOrNull() ?: length
            if (parts[0].isNotBlank() && !parts.getOrNull(1).isNullOrBlank()) end = minOf(end, parts[1].toLongOrNull() ?: -1)
            if (start < 0 || start >= length || end < start) return WebResourceResponse("text/plain", "UTF-8", 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$length"), ByteArrayInputStream(ByteArray(0)))
        }
        val count = if (length == 0L) 0 else end - start + 1
        val headers = mutableMapOf("Accept-Ranges" to "bytes", "Content-Length" to count.toString(), "Cache-Control" to "no-store")
        if (range != null) headers["Content-Range"] = "bytes $start-$end/$length"
        return WebResourceResponse(if (session.entry.pdf) "application/pdf" else "application/octet-stream", null, if (range == null) 200 else 206, if (range == null) "OK" else "Partial Content", headers, FileRange(file, start, count))
    }

    private fun evaluate(script: String) { if (!destroyed) view.evaluateJavascript(script, null) }
    fun save(copyName: String? = null) {
        if (!ready || session.busy) return
        session.busy = true
        evaluate("window.NotePit?.requestSave(${if (copyName == null) "null" else JSONObject.quote(copyName)})")
    }
    fun command(command: String) = evaluate("window.NotePit?.command(${JSONObject.quote(command)})")
    suspend fun revealPending() {
        if (!ready || destroyed || sessions.activeId != session.id) return
        val pending = session.pendingReveal ?: return
        sessions.verifySearchSource(session)
        if (!ready || destroyed || sessions.activeId != session.id || session.pendingReveal !== pending) return
        if (session.externalChanged && !session.dirty) {
            // A search click opens the current original, but never discards an edited buffer.
            flush().await()
            if (destroyed || sessions.activeId != session.id || session.pendingReveal !== pending) return
            if (!session.dirty) {
                destroy()
                sessions.reload(session)
                return
            }
        }
        val changed = session.dirty || session.externalChanged || session.error != null ||
            pending.optLong("sourceModified") != session.entry.modified || pending.optLong("sourceSize") != session.entry.size
        val location = JSONObject(pending.toString()).put("sourceChanged", changed)
        location.remove("sourceModified")
        location.remove("sourceSize")
        if (changed) sessions.message = "Search coordinates describe an indexed original, not necessarily this buffer. Verify the highlighted source; your draft is retained."
        evaluate("window.NotePit?.reveal($location)")
        sessions.revealDelivered(session, pending)
    }
    fun theme(dark: Boolean) {
        if (darkMode == dark) return
        darkMode = dark
        evaluate("window.NotePit?.setTheme($dark)")
    }
    fun flush(): CompletableDeferred<Unit> {
        if (!ready || destroyed) return CompletableDeferred(Unit)
        val id = (++nextCheckpoint).toString()
        val result = CompletableDeferred<Unit>()
        checkpoints[id] = result
        evaluate("window.NotePit?.flushDraft(${JSONObject.quote(id)})")
        return result
    }
    private fun completeCheckpoint(id: String, error: Exception?) {
        val result = checkpoints.remove(id) ?: return
        if (error == null) result.complete(Unit) else result.completeExceptionally(error)
    }
    fun destroy() {
        if (destroyed) return
        destroyed = true
        checkpoints.values.forEach { it.cancel() }; checkpoints.clear()
        view.stopLoading()
        WebViewCompat.removeWebMessageListener(view, "NotePitNative")
        view.loadUrl("about:blank")
        view.removeAllViews()
        view.destroy()
    }
}

private class FileRange(file: java.io.File, start: Long, private var remaining: Long) : InputStream() {
    private val source = RandomAccessFile(file, "r").apply { seek(start) }
    override fun read(): Int {
        if (remaining == 0L) return -1
        val value = source.read()
        if (value >= 0) remaining-- else remaining = 0
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) remaining -= count else remaining = 0
        return count
    }
    override fun close() { source.close() }
}
