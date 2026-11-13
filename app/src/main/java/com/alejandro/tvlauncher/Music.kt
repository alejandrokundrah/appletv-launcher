package com.alejandro.tvlauncher

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Bundled Weav-Tune web player. The production web build is shipped in
 * app/src/main/assets/music, so this screen never depends on a browser or an
 * installable browser extension.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "DEPRECATION")
@Composable
fun MusicApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val web = remember {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(android.graphics.Color.BLACK)
            addJavascriptInterface(AndroidMusicBridge(), "AndroidOpenTune")
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("file:///android_asset/music/index.html")
        }
    }

    DisposableEffect(web) {
        onDispose {
            web.stopLoading()
            web.destroy()
        }
    }

    BackHandler {
        if (web.canGoBack()) web.goBack() else onClose()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { web },
            modifier = Modifier.fillMaxSize(),
            update = { it.requestFocus() },
        )
    }
}

private class AndroidMusicBridge {
    @JavascriptInterface
    fun platform(): String = "android-tv-bundled"
}
