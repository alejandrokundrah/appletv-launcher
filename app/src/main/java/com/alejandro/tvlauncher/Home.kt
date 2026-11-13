package com.alejandro.tvlauncher

import android.text.format.DateFormat
import android.view.SoundEffectConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeContent(
    apps: List<AppEntry>,
    focused: AppEntry?,
    content: List<ContentItem>,
    trendingMovies: List<TmdbTitle>,
    settings: LauncherSettings,
    weather: WeatherInfo?,
    net: NetInfo?,
    homeSignal: Int,
    restoreTo: String?,
    onFocusedApp: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onLaunchContent: (ContentItem) -> Unit,
    onOpenTrendingMovie: (TmdbTitle) -> Unit,
    onOptions: (AppEntry) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenQuick: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // A launcher must never "go back" to anything.
    BackHandler(enabled = true) { }

    val listState = rememberLazyListState()
    val firstTile = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val view = LocalView.current
    val lastIndex = remember { intArrayOf(-1) }

    fun click() {
        if (settings.sounds) view.playSoundEffect(SoundEffectConstants.CLICK)
    }

    // Grab focus once the row is on screen, and again whenever Home is pressed. After an overlay
    // (options menu, search, quick settings) closes, [restoreTo] puts focus back on the tile it came from.
    LaunchedEffect(apps.isNotEmpty(), homeSignal, restoreTo) {
        if (apps.isNotEmpty()) {
            lastIndex[0] = -1
            val idx = restoreTo?.let { pkg -> apps.indexOfFirst { it.packageName == pkg } }?.takeIf { it >= 0 } ?: 0
            listState.scrollToItem(idx)
            withFrameNanos { }
            val target = if (idx == 0) firstTile else requesters[apps[idx].packageName]
            runCatching { target?.requestFocus() }
        }
    }

    // Size tiles relative to the screen so they look the same on any box / resolution / density.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val tileWidth: Dp = (screenWidthDp * when (settings.tileSize) {
        0 -> 0.105f
        2 -> 0.165f
        else -> 0.13f
    }).dp
    val compact = screenWidthDp < 1100

    Column(Modifier.fillMaxSize()) {
        // Top bar: weather on the left; network, clock and settings on the right
        Row(
            Modifier.fillMaxWidth().padding(top = 32.dp, start = 72.dp, end = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (weather != null) WeatherPill(weather, compact)
            Spacer(Modifier.weight(1f))
            if (net != null) {
                NetworkPill(net, compact)
                Spacer(Modifier.width(14.dp))
            }
            ClockPill(settings, compact)
            Spacer(Modifier.width(14.dp))
            TopButton(onOpenSearch) { drawSearchIcon() }
            Spacer(Modifier.width(14.dp))
            TopButton(onOpenQuick) { drawQuickIcon() }
            Spacer(Modifier.width(14.dp))
            TopButton(onOpenSettings) { drawSlidersIcon() }
        }

        Spacer(Modifier.weight(1f))

        // Hero text for the focused app
        Column(Modifier.padding(start = 80.dp, end = 80.dp)) {
            if (focused == null) {
                GText(
                    stringResource(if (apps.isEmpty()) R.string.home_no_apps else R.string.home_loading),
                    30.sp, FontWeight.Medium, Color.White.copy(alpha = 0.7f), shadow = true,
                )
            } else {
                AnimatedContent(
                    targetState = focused.label,
                    transitionSpec = {
                        (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 6 }) togetherWith
                            (fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 6 })
                    },
                    label = "title",
                ) { title ->
                    GText(title, 48.sp, FontWeight.Bold, Color.White, maxLines = 1, shadow = true)
                }
                Spacer(Modifier.height(6.dp))
                GText(
                    stringResource(R.string.home_hint),
                    19.sp, FontWeight.Normal, Color.White.copy(alpha = 0.62f), shadow = true,
                )
            }
        }

        // What you've watched / what the focused app is recommending (e.g. Netflix "Continue watching").
        // It sits above the tiles; press Up from a tile to move into it, Down to come back.
        AnimatedVisibility(
            visible = content.isNotEmpty(),
            enter = fadeIn(tween(250)) + expandVertically(tween(250)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
        ) {
            ContentRow(content, tileWidth, onLaunchContent)
        }

        if (trendingMovies.isNotEmpty()) {
            TrendingMoviesRow(trendingMovies, tileWidth, lite = LocalGlass.current.lite, onOpen = onOpenTrendingMovie)
        }

        Spacer(Modifier.height(14.dp))

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 80.dp, vertical = 30.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                AppTile(
                    app = app,
                    width = tileWidth,
                    alwaysShowLabel = settings.labels == 1,
                    pinned = app.packageName in settings.pinned,
                    ring = if (settings.accentFocus) lerp(app.accent, Color.White, 0.55f) else Color.White,
                    requester = if (index == 0) firstTile else requesters.getOrPut(app.packageName) { FocusRequester() },
                    onFocused = {
                        if (settings.sounds && lastIndex[0] >= 0 && lastIndex[0] != index) {
                            view.playSoundEffect(
                                if (index > lastIndex[0]) SoundEffectConstants.NAVIGATION_RIGHT
                                else SoundEffectConstants.NAVIGATION_LEFT
                            )
                        }
                        lastIndex[0] = index
                        onFocusedApp(app.packageName)
                    },
                    onLaunch = {
                        click()
                        onLaunch(app)
                    },
                    onOptions = { onOptions(app) },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
    }
}

/** Weekly TMDB movie trends, shown on the launcher homepage independently of installed apps. */
@Composable
private fun TrendingMoviesRow(items: List<TmdbTitle>, tileWidth: Dp, lite: Boolean, onOpen: (TmdbTitle) -> Unit) {
    val cards = items.map { title ->
        ContentItem(
            packageName = "tmdb:${title.id}",
            title = title.title,
            posterUri = title.poster?.let { Tmdb.image(it, "w342") },
            intentUri = null,
            watched = false,
            lastEngagement = 0L,
        )
    }
    Column(Modifier.padding(top = 10.dp)) {
        GText(
            stringResource(R.string.home_trending_movies),
            20.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.85f),
            modifier = Modifier.padding(start = 80.dp, bottom = 6.dp),
            shadow = true,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 80.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(cards, key = { _, item -> item.packageName }) { index, item ->
                ContentCard(item, tileWidth * 0.85f, tileWidth * 0.85f * 9 / 16, lite) {
                    onOpen(items[index])
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: AppEntry,
    width: Dp,
    alwaysShowLabel: Boolean,
    pinned: Boolean,
    ring: Color,
    requester: FocusRequester?,
    onFocused: () -> Unit,
    onLaunch: () -> Unit,
    onOptions: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val lite = LocalGlass.current.lite
    // Kept as State (not `by`) and only read inside draw / layer lambdas below, so a focus animation
    // redraws the tile each frame without recomposing it. That was a lot of work on weak GPUs/CPUs.
    val scale = animateFloatAsState(
        targetValue = if (focused) 1.12f else 1f,
        animationSpec = if (lite) tween(160) else spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "scale",
    )
    val glow = animateFloatAsState(if (focused) 1f else 0f, tween(if (lite) 120 else 220), label = "glow")
    val height = width * 9 / 16
    val corner = 24.dp
    val shape = RoundedCornerShape(corner)

    Column(
        modifier = Modifier.width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width, height)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .glassLive(corner, emphasis = { glow.value }, rim = false, glowColor = ring)
                .clip(shape)
                // Order matters: focus observers must sit before the focusable (clickable).
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .combinedClickable(onClick = onLaunch, onLongClick = onOptions),
            contentAlignment = Alignment.Center,
        ) {
            if (app.banner != null) {
                Image(
                    bitmap = app.banner,
                    contentDescription = app.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(
                    bitmap = app.icon,
                    contentDescription = app.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(height * 0.50f)
                        .clip(RoundedCornerShape(height * 0.12f)),
                )
            }
            // Glossy top highlight over artwork (skipped in lite mode: one less blended layer per tile)
            if (!lite) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(height * 0.5f)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.16f), Color.Transparent)
                            )
                        )
                )
            }
            // Light-catching rim on top of everything
            Box(Modifier.fillMaxSize().glassRim(corner))
            // Focus ring (alpha read while drawing)
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val a = glow.value
                        if (a > 0.01f) {
                            val sw = 3.dp.toPx()
                            drawRoundRect(
                                color = ring.copy(alpha = 0.95f * a),
                                topLeft = Offset(sw / 2, sw / 2),
                                size = Size(size.width - sw, size.height - sw),
                                cornerRadius = CornerRadius(corner.toPx() - sw / 2),
                                style = Stroke(width = sw),
                            )
                        }
                    }
            )
            // Small dot marks pinned apps
            if (pinned) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(12.dp)
                        .background(Color.White.copy(alpha = 0.92f), CircleShape)
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        GText(
            text = app.label,
            size = 19.sp,
            weight = FontWeight.Medium,
            color = Color.White.copy(alpha = if (alwaysShowLabel) 0.9f else 0.95f),
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (alwaysShowLabel) 1f else glow.value },
            align = TextAlign.Center,
            shadow = true,
        )
    }
}

/** Row of poster cards for the focused app. OK on a card opens that title in the app. */
@Composable
private fun ContentRow(items: List<ContentItem>, tileWidth: Dp, onOpen: (ContentItem) -> Unit) {
    val cardW = tileWidth * 0.85f
    val cardH = cardW * 9 / 16
    val lite = LocalGlass.current.lite
    Column(Modifier.padding(top = 10.dp)) {
        GText(
            stringResource(if (items.any { it.watched }) R.string.row_continue else R.string.row_recommended),
            20.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.85f),
            modifier = Modifier.padding(start = 80.dp, bottom = 6.dp),
            shadow = true,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 80.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(items, key = { i, c -> "${c.title}#$i" }) { _, item ->
                ContentCard(item, cardW, cardH, lite) { onOpen(item) }
            }
        }
    }
}

@Composable
internal fun ContentCard(
    item: ContentItem,
    w: Dp,
    h: Dp,
    lite: Boolean,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val poster by produceState<ImageBitmap?>(null, item.posterUri) {
        value = item.posterUri?.let { PosterLoader.load(context, it) }
    }
    var focused by remember { mutableStateOf(false) }
    val glow = animateFloatAsState(if (focused) 1f else 0f, tween(if (lite) 100 else 180), label = "card")
    val corner = 14.dp

    Column(Modifier.width(w)) {
        Box(
            modifier = Modifier
                .size(w, h)
                .graphicsLayer {
                    val s = 1f + 0.08f * glow.value
                    scaleX = s
                    scaleY = s
                }
                .clip(RoundedCornerShape(corner))
                .background(Color.White.copy(alpha = 0.10f))
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .drawWithContent {
                    drawContent()
                    val a = glow.value
                    if (a > 0.01f) {
                        val sw = 3.dp.toPx()
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.95f * a),
                            topLeft = Offset(sw / 2, sw / 2),
                            size = Size(size.width - sw, size.height - sw),
                            cornerRadius = CornerRadius(corner.toPx() - sw / 2),
                            style = Stroke(width = sw),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val p = poster
            if (p != null) {
                Image(
                    bitmap = p,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // No artwork (or still loading): show the title so the card is still useful.
                GText(
                    item.title, 16.sp, FontWeight.Medium, Color.White.copy(alpha = 0.9f),
                    maxLines = 3, align = TextAlign.Center,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        GText(
            item.title, 15.sp, FontWeight.Normal, Color.White.copy(alpha = 0.85f),
            maxLines = 1, modifier = Modifier.fillMaxWidth(), shadow = true,
        )
        if (subtitle != null) {
            GText(
                subtitle, 13.sp, FontWeight.Normal, Color.White.copy(alpha = 0.55f),
                maxLines = 1, modifier = Modifier.fillMaxWidth(), shadow = true,
            )
        }
    }
}

@Composable
private fun ClockPill(settings: LauncherSettings, compact: Boolean) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000L * (60 - (System.currentTimeMillis() / 1000 % 60)))
        }
    }
    val pattern = when (settings.clock) {
        1 -> "h:mm"
        2 -> "HH:mm"
        else -> if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    }
    val time = SimpleDateFormat(pattern, Locale.getDefault()).format(now)
    val date = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now)

    Row(
        Modifier.glass(28.dp).padding(horizontal = 26.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GText(time, 30.sp, FontWeight.SemiBold, Color.White)
        if (!compact) {
            Spacer(Modifier.width(14.dp))
            GText(date, 16.sp, FontWeight.Normal, Color.White.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun TopButton(onClick: () -> Unit, icon: DrawScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val e by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "gear")
    val shape = RoundedCornerShape(32.dp)

    Box(
        modifier = Modifier
            .size(64.dp)
            .graphicsLayer {
                val s = 1f + 0.12f * e
                scaleX = s
                scaleY = s
            }
            .glass(32.dp, emphasis = e)
            .border(2.dp, Color.White.copy(alpha = 0.9f * e), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Icons are drawn by hand so we don't need an icon library
        Canvas(Modifier.size(28.dp)) { icon() }
    }
}

/** "Sliders" icon: settings. */
private fun DrawScope.drawSlidersIcon() {
    val ys = listOf(0.2f, 0.5f, 0.8f)
    val knobs = listOf(0.68f, 0.30f, 0.55f)
    ys.forEachIndexed { i, y ->
        drawLine(
            color = Color.White.copy(alpha = 0.65f),
            start = Offset(0f, size.height * y),
            end = Offset(size.width, size.height * y),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = Color.White,
            radius = 5.5.dp.toPx(),
            center = Offset(size.width * knobs[i], size.height * y),
        )
    }
}

/** Magnifying glass: search. */
private fun DrawScope.drawSearchIcon() {
    val r = size.width * 0.30f
    val c = Offset(size.width * 0.42f, size.height * 0.42f)
    drawCircle(color = Color.White, radius = r, center = c, style = Stroke(width = 3.dp.toPx()))
    drawLine(
        color = Color.White,
        start = Offset(c.x + r * 0.72f, c.y + r * 0.72f),
        end = Offset(size.width * 0.92f, size.height * 0.92f),
        strokeWidth = 3.5.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

/** Four rounded squares: quick settings. */
private fun DrawScope.drawQuickIcon() {
    val gap = size.width * 0.12f
    val s = (size.width - gap) / 2f
    for (row in 0..1) for (col in 0..1) {
        drawRoundRect(
            color = Color.White.copy(alpha = if (row == 0 && col == 0) 1f else 0.7f),
            topLeft = Offset(col * (s + gap), row * (s + gap)),
            size = Size(s, s),
            cornerRadius = CornerRadius(s * 0.28f),
        )
    }
}
