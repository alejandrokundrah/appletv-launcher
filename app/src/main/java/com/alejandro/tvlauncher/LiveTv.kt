package com.alejandro.tvlauncher

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ------------------------------------------------------------------------------------ data

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    /** `group-title` of the playlist entry, split on `;` (a channel can sit in several categories). */
    val groups: List<String>,
    val userAgent: String?,
    val referrer: String?,
)

/**
 * Downloads the iptv-org playlist (cached for 12 hours, and reused when the download fails) and turns
 * it into [Channel]s. The playlist is large, so it is parsed line by line from disk.
 */
object IptvRepo {

    private const val MAX_AGE_MS = 12L * 60 * 60 * 1000
    private val ATTR = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    suspend fun load(context: Context): List<Channel> = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "iptv_index.m3u")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS
        if (!fresh) runCatching { download(cache) }
        if (!cache.exists()) return@withContext emptyList<Channel>()
        runCatching { cache.bufferedReader().use { parse(it) } }.getOrDefault(emptyList())
    }

    private fun download(target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        val conn = URL(MiniConfig.IPTV_M3U).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 25_000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() > 0) {
                target.delete()
                tmp.renameTo(target)
            }
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    internal fun parse(reader: BufferedReader): List<Channel> {
        val out = ArrayList<Channel>(8192)
        var name: String? = null
        var logo: String? = null
        var groups: List<String> = emptyList()
        var ua: String? = null
        var ref: String? = null
        while (true) {
            val line = (reader.readLine() ?: break).trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF")) {
                // Attribute values may contain commas, so the name starts after the first comma that follows the last quote.
                val lastQuote = line.lastIndexOf('"')
                val comma = line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
                val head = if (comma >= 0) line.substring(0, comma) else line
                name = if (comma >= 0) line.substring(comma + 1).trim() else null
                val attrs = ATTR.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") }
                groups = (attrs["group-title"] ?: "").split(';').map { it.trim() }.filter { it.isNotEmpty() }
                ua = null
                ref = null
            } else if (line.startsWith("#EXTVLCOPT:")) {
                val kv = line.removePrefix("#EXTVLCOPT:")
                val eq = kv.indexOf('=')
                if (eq > 0) {
                    val k = kv.substring(0, eq).trim().lowercase()
                    val v = kv.substring(eq + 1).trim()
                    if (k == "http-user-agent") ua = v
                    if (k == "http-referrer" || k == "http-referer") ref = v
                }
            } else if (line.startsWith("#")) {
                // other directive: nothing to do
            } else {
                val n = name
                if (n != null && (line.startsWith("http://") || line.startsWith("https://"))) {
                    out += Channel(
                        name = n.ifEmpty { line },
                        url = line,
                        logo = logo,
                        groups = groups.ifEmpty { listOf("Undefined") },
                        userAgent = ua,
                        referrer = ref,
                    )
                }
                name = null
                ua = null
                ref = null
            }
        }
        return out
    }
}

// ------------------------------------------------------------------------------------ screens

/** Live TV mini app: browse the playlist by category / search, OK plays, Up / Down zaps. */
@Composable
fun LiveTvApp(lite: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var playIndex by remember { mutableIntStateOf(-1) }
    var focusIndex by remember { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(reload) {
        failed = false
        channels = null
        val list = IptvRepo.load(context)
        if (list.isEmpty()) failed = true else channels = list
    }

    val all = channels
    val categories = remember(all) {
        all?.flatMap { it.groups }?.distinct()?.sortedBy { it.lowercase() } ?: emptyList()
    }
    val filtered = remember(all, category, query) {
        val q = query.trim().lowercase()
        all?.filter { ch ->
            (category == null || category in ch.groups) && (q.isEmpty() || q in ch.name.lowercase())
        } ?: emptyList()
    }

    if (playIndex in filtered.indices) {
        LivePlayer(
            list = filtered,
            startIndex = playIndex,
            onIndexChange = {
                playIndex = it
                focusIndex = it
            },
            onBack = { playIndex = -1 },
        )
    } else {
        ChannelBrowser(
            loaded = all != null,
            failed = failed,
            total = all?.size ?: 0,
            filtered = filtered,
            categories = categories,
            category = category,
            onCategory = { category = it },
            query = query,
            onQuery = { query = it },
            gridState = gridState,
            focusIndex = focusIndex,
            onPlay = { index ->
                focusIndex = index
                playIndex = index
            },
            onRetry = { reload++ },
            onClose = onClose,
        )
    }
}

@Composable
private fun ChannelBrowser(
    loaded: Boolean,
    failed: Boolean,
    total: Int,
    filtered: List<Channel>,
    categories: List<String>,
    category: String?,
    onCategory: (String?) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    focusIndex: Int,
    onPlay: (Int) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = true) { onClose() }
    val gridFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    // Back from the player: land on the channel that was playing.
    LaunchedEffect(loaded) {
        if (loaded && filtered.isNotEmpty()) {
            gridState.scrollToItem(focusIndex.coerceIn(0, filtered.lastIndex))
            withFrameNanos { }
            runCatching { gridFocus.requestFocus() }
        }
    }
    LaunchedEffect(failed) {
        if (failed) {
            withFrameNanos { }
            runCatching { retryFocus.requestFocus() }
        }
    }
    // A new category / search starts at the top (but not when coming back from the player).
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(category, query) {
        if (seen) gridState.scrollToItem(0) else seen = true
    }

    Box(Modifier.fillMaxSize().background(MiniScrim)) {
        Column(Modifier.fillMaxSize().padding(top = 36.dp, start = 64.dp, end = 64.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GText(stringResource(R.string.live_title), 40.sp, FontWeight.Bold, Color.White, maxLines = 1)
                Spacer(Modifier.width(28.dp))
                MiniSearchField(
                    value = query,
                    onValueChange = onQuery,
                    hint = stringResource(R.string.live_search_hint),
                    modifier = Modifier.width(420.dp),
                )
                Spacer(Modifier.weight(1f))
                if (loaded) {
                    GText(
                        if (filtered.size == total) "$total" else "${filtered.size} / $total",
                        18.sp, FontWeight.Medium, Color.White.copy(alpha = 0.6f), maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            when {
                failed -> {
                    MiniMessage(stringResource(R.string.live_failed))
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        MiniButton(stringResource(R.string.live_retry), primary = true, requester = retryFocus, onClick = onRetry)
                    }
                }
                !loaded -> MiniMessage(stringResource(R.string.live_loading))
                else -> {
                    val chips = remember(categories) { listOf<String?>(null) + categories }
                    LazyRow(
                        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(chips, key = { it ?: "" }) { c ->
                            MiniButton(
                                text = c ?: stringResource(R.string.live_all),
                                selected = c == category,
                                onClick = { onCategory(c) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (filtered.isEmpty()) {
                        MiniMessage(stringResource(R.string.live_none))
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 210.dp),
                            state = gridState,
                            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(filtered) { index, ch ->
                                ChannelCard(
                                    ch = ch,
                                    requester = if (index == focusIndex) gridFocus else null,
                                    onClick = { onPlay(index) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(ch: Channel, requester: FocusRequester?, onClick: () -> Unit) {
    val context = LocalContext.current
    val logo by produceState<ImageBitmap?>(null, ch.logo) {
        value = ch.logo?.let { PosterLoader.load(context, it) }
    }
    var focused by remember { mutableStateOf(false) }
    val e by animateFloatAsState(if (focused) 1f else 0f, tween(140), label = "channel")
    val shape = RoundedCornerShape(16.dp)

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer {
                    val s = 1f + 0.06f * e
                    scaleX = s
                    scaleY = s
                }
                .clip(shape)
                .background(Color.White.copy(alpha = 0.10f + 0.12f * e))
                .border(3.dp, Color.White.copy(alpha = 0.95f * e), shape)
                .onFocusChanged { focused = it.isFocused }
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val l = logo
            if (l != null) {
                Image(
                    bitmap = l,
                    contentDescription = ch.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            } else {
                GText(
                    ch.name, 16.sp, FontWeight.Medium, Color.White.copy(alpha = 0.85f),
                    maxLines = 3, align = TextAlign.Center, modifier = Modifier.padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        GText(
            ch.name, 15.sp, FontWeight.Normal, Color.White.copy(alpha = 0.88f),
            maxLines = 1, modifier = Modifier.fillMaxWidth(), shadow = true,
        )
    }
}

// ------------------------------------------------------------------------------------ player

@OptIn(UnstableApi::class)
private fun buildSource(ch: Channel): MediaSource {
    val headers = HashMap<String, String>()
    ch.referrer?.let { headers["Referer"] = it }
    val factory = DefaultHttpDataSource.Factory()
        .setUserAgent(
            ch.userAgent
                ?: "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        )
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)
        .setDefaultRequestProperties(headers)

    // Nearly everything in the playlist is HLS. Plain files / raw MPEG-TS streams go through the progressive source.
    val path = ch.url.lowercase().substringBefore('?')
    val progressive = listOf(".mp4", ".mkv", ".ts", ".avi", ".flv", ".mp3", ".aac").any { path.endsWith(it) }
    val item = MediaItem.Builder()
        .setUri(ch.url)
        .apply { if (!progressive) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .build()
    return if (progressive) {
        ProgressiveMediaSource.Factory(factory).createMediaSource(item)
    } else {
        HlsMediaSource.Factory(factory).createMediaSource(item)
    }
}

/**
 * Full-screen player. Up / Channel+ = next channel, Down / Channel- = previous, OK shows the channel
 * banner again, Back returns to the list.
 */
@OptIn(UnstableApi::class)
@Composable
private fun LivePlayer(
    list: List<Channel>,
    startIndex: Int,
    onIndexChange: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var index by remember { mutableIntStateOf(startIndex) }
    val ch = list[index.coerceIn(0, list.lastIndex)]
    var buffering by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var infoVisible by remember { mutableStateOf(true) }
    var infoTick by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    val exo = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) failed = false
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
                buffering = false
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    // Pause when the launcher is covered by another app, resume when it comes back.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, exo) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exo.pause()
            if (event == Lifecycle.Event.ON_START) exo.play()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(ch) {
        failed = false
        buffering = true
        exo.setMediaSource(buildSource(ch))
        exo.prepare()
        exo.playWhenReady = true
        onIndexChange(index)
    }
    LaunchedEffect(infoTick, ch) {
        infoVisible = true
        delay(4000)
        infoVisible = false
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(enabled = true) { onBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionUp, Key.ChannelUp, Key.PageUp -> {
                        index = (index + 1) % list.size
                        true
                    }
                    Key.DirectionDown, Key.ChannelDown, Key.PageDown -> {
                        index = (index - 1 + list.size) % list.size
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.DirectionLeft, Key.DirectionRight -> {
                        infoTick++
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = exo
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (failed) {
            Box(Modifier.fillMaxSize().padding(horizontal = 120.dp), contentAlignment = Alignment.Center) {
                GText(
                    stringResource(R.string.live_unavailable), 28.sp, FontWeight.Medium,
                    Color.White, align = TextAlign.Center, shadow = true,
                )
            }
        } else if (buffering) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GText(stringResource(R.string.live_buffering), 26.sp, FontWeight.Medium, Color.White.copy(alpha = 0.85f), shadow = true)
            }
        }

        AnimatedVisibility(
            visible = infoVisible || failed,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Column(
                Modifier
                    .padding(start = 56.dp, bottom = 48.dp)
                    .glass(28.dp, darken = 0.30f)
                    .padding(horizontal = 32.dp, vertical = 22.dp)
            ) {
                GText(ch.name, 34.sp, FontWeight.Bold, Color.White, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                GText(
                    ch.groups.joinToString(" · ") + "   ${index + 1} / ${list.size}",
                    19.sp, FontWeight.Normal, Color.White.copy(alpha = 0.75f), maxLines = 1,
                )
                Spacer(Modifier.height(10.dp))
                GText(stringResource(R.string.live_hint), 16.sp, FontWeight.Normal, Color.White.copy(alpha = 0.55f), maxLines = 1)
            }
        }
    }
}
