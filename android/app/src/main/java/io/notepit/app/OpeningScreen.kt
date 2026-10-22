package io.notepit.app

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import kotlin.math.abs

/** Decorative local artwork only: no scripting, bridge, storage or remote requests. */
@SuppressLint("ClickableViewAccessibility")
@Composable
internal fun OpeningScreen(onOpen: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val open by rememberUpdatedState(onOpen)
    val view = remember {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.rgb(17, 20, 31))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            settings.apply {
                javaScriptEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
            }
            val assets = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context.applicationContext)).build()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    (if (request.url.scheme == "https" && request.url.host == "appassets.androidplatform.net" &&
                        (request.url.path == "/assets/entry/index.html" || request.url.path == "/assets/entry/art.svg")) {
                        assets.shouldInterceptRequest(request.url)
                    } else null)
                    ?: WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
            var startX = 0f
            var startY = 0f
            var tracking = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y; tracking = true }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false
                    MotionEvent.ACTION_UP -> {
                        if (tracking && startY - event.y >= 64 * resources.displayMetrics.density && startY - event.y > abs(event.x - startX) * 1.4f) open()
                        tracking = false
                    }
                }
                true
            }
            loadUrl("https://appassets.androidplatform.net/assets/entry/index.html")
        }
    }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) view.onResume()
            else if (event == Lifecycle.Event.ON_PAUSE) view.onPause()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); view.stopLoading(); view.destroy() }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().semantics { contentDescription = "NotePit"; heading() })
        OutlinedIconButton(onClick = { open() }, border = BorderStroke(1.dp, Color(0x66E3E5F3)),
            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = Color(0xBD11141F)),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp).size(52.dp)) {
            Icon(Icons.Outlined.KeyboardArrowUp, "Open NotePit", tint = Color(0xFFFFB0CE))
        }
    }
}
