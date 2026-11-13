package com.alejandro.tvlauncher

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SportsEvent(
    val name: String,
    val category: String,
    val league: String,
    val sources: List<SportsSource>,
)

data class SportsSource(val quality: String, val url: String, val live: Boolean, val broadcast: String?)

private object SportsRepo {
    private const val API = "https://streamfree.top/api/v1/streams"

    suspend fun load(): List<SportsEvent> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(API).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.inputStream.bufferedReader().use { reader ->
                val root = JSONObject(reader.readText())
                val streams = root.optJSONArray("streams") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until streams.length()) {
                        val item = streams.optJSONObject(i) ?: continue
                        val sourcesJson = item.optJSONArray("sources") ?: continue
                        val sources = buildList {
                            for (j in 0 until sourcesJson.length()) {
                                val source = sourcesJson.optJSONObject(j) ?: continue
                                val url = source.optString("url").takeIf { it.startsWith("http") } ?: continue
                                add(SportsSource(
                                    quality = source.optString("quality", "LIVE"),
                                    url = url,
                                    live = source.optBoolean("live"),
                                    broadcast = source.optString("broadcast").takeIf { it.isNotBlank() && it != "null" },
                                ))
                            }
                        }
                        if (sources.any { it.live }) add(SportsEvent(
                            name = item.optString("name", "Live event"),
                            category = item.optString("category", "sports"),
                            league = item.optString("league"),
                            sources = sources,
                        ))
                    }
                }
            }.also { connection.disconnect() }
        }.getOrDefault(emptyList())
    }
}

@Composable
fun SportsApp(lite: Boolean, onClose: () -> Unit) {
    var events by remember { mutableStateOf<List<SportsEvent>?>(null) }
    var selected by remember { mutableStateOf<SportsEvent?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    BackHandler { if (selected != null) selected = null else onClose() }
    LaunchedEffect(reload) { events = SportsRepo.load() }

    val all = events
    if (selected != null) {
        SportsPlayer(selected!!, onClose = { selected = null })
        return
    }

    val categories = all.orEmpty().map { it.category }.distinct().sorted()
    val visible = all.orEmpty().filter { category == null || it.category == category }
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0F1A)).padding(horizontal = 36.dp, vertical = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GText("Live Sports", 30.sp, androidx.compose.ui.text.font.FontWeight.Bold, Color.White)
                GText(if (events == null) "Loading live events…" else "${visible.size} events live now", 15.sp, androidx.compose.ui.text.font.FontWeight.Normal, Color.White.copy(alpha = .65f))
            }
            Box(Modifier.border(1.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(10.dp)).clickable { reload++ }.padding(horizontal = 18.dp, vertical = 10.dp)) {
                GText("Refresh", 15.sp, androidx.compose.ui.text.font.FontWeight.SemiBold, Color.White)
            }
        }
        Spacer(Modifier.height(20.dp))
        if (categories.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item { SportsChip("All", category == null) { category = null } }
                items(categories) { value -> SportsChip(value.replaceFirstChar { it.uppercase() }, category == value) { category = value } }
            }
            Spacer(Modifier.height(18.dp))
        }
        if (events == null) {
            GText("Finding live streams…", 20.sp, androidx.compose.ui.text.font.FontWeight.Medium, Color.White.copy(alpha = .75f))
        } else if (visible.isEmpty()) {
            GText("No live events are available right now.", 20.sp, androidx.compose.ui.text.font.FontWeight.Medium, Color.White.copy(alpha = .75f))
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(280.dp), contentPadding = PaddingValues(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(visible) { event -> SportsCard(event) { selected = event } }
            }
        }
    }
}

@Composable
private fun SportsChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.background(if (active) Color(0xFFEC4B67) else Color.White.copy(alpha = .1f), RoundedCornerShape(18.dp)).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 9.dp)) {
        GText(label, 14.sp, androidx.compose.ui.text.font.FontWeight.SemiBold, Color.White)
    }
}

@Composable
private fun SportsCard(event: SportsEvent, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val source = event.sources.firstOrNull { it.live } ?: event.sources.firstOrNull()
    Box(Modifier.fillMaxWidth().background(Color(0xFF171D2B), RoundedCornerShape(14.dp)).border(2.dp, if (focused) Color(0xFFFF5874) else Color.Transparent, RoundedCornerShape(14.dp)).onFocusChanged { focused = it.isFocused }.focusable().clickable(enabled = source != null) { onClick() }.padding(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GText("● LIVE  ${event.category.uppercase()}", 13.sp, androidx.compose.ui.text.font.FontWeight.Bold, Color(0xFFFF5874))
            GText(event.name, 20.sp, androidx.compose.ui.text.font.FontWeight.Bold, Color.White, maxLines = 2)
            if (event.league.isNotBlank()) GText(event.league, 14.sp, androidx.compose.ui.text.font.FontWeight.Normal, Color.White.copy(alpha = .62f), maxLines = 1)
            GText("${event.sources.count { it.live }} live source${if (event.sources.count { it.live } == 1) "" else "s"}  ·  ${source?.quality ?: "Unavailable"}", 13.sp, androidx.compose.ui.text.font.FontWeight.Medium, Color.White.copy(alpha = .78f))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SportsPlayer(event: SportsEvent, onClose: () -> Unit) {
    val context = LocalContext.current
    val source = event.sources.firstOrNull { it.live } ?: event.sources.firstOrNull()
    if (source == null) { onClose(); return }
    val web = remember(source.url) {
        WebView(context).apply {
            setBackgroundColor(AColor.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && request.url.host?.lowercase() !in setOf(
                        "streamfree.top", "www.streamfree.top", "strmfree.st", "www.strmfree.st",
                    )
            }
            loadUrl(source.url)
        }
    }
    AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
}
