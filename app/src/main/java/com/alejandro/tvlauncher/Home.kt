package com.alejandro.tvlauncher

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    settings: LauncherSettings,
    homeSignal: Int,
    onFocusedIndex: (Int) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onOptions: (AppEntry) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // A launcher must never "go back" to anything.
    BackHandler(enabled = true) { }

    val listState = rememberLazyListState()
    val firstTile = remember { FocusRequester() }

    // Grab focus once the row is on screen, and again whenever Home is pressed.
    LaunchedEffect(apps.isNotEmpty(), homeSignal) {
        if (apps.isNotEmpty()) {
            listState.scrollToItem(0)
            withFrameNanos { }
            runCatching { firstTile.requestFocus() }
        }
    }

    val tileWidth: Dp = when (settings.tileSize) {
        0 -> 224.dp
        2 -> 328.dp
        else -> 272.dp
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar: clock + settings button
        Row(
            Modifier.fillMaxWidth().padding(top = 36.dp, start = 72.dp, end = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            ClockPill(settings)
            Spacer(Modifier.width(18.dp))
            SettingsButton(onOpenSettings)
        }

        Spacer(Modifier.weight(1f))

        // Hero text for the focused app
        Column(Modifier.padding(start = 80.dp, end = 80.dp)) {
            if (focused == null) {
                GText(
                    if (apps.isEmpty()) "No apps to show" else "Loading apps…",
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
                    GText(title, 60.sp, FontWeight.Bold, Color.White, maxLines = 1, shadow = true)
                }
                Spacer(Modifier.height(6.dp))
                GText(
                    "Press OK to open   ·   Hold OK for app info   ·   Menu for settings",
                    19.sp, FontWeight.Normal, Color.White.copy(alpha = 0.62f), shadow = true,
                )
            }
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
                    requester = if (index == 0) firstTile else null,
                    onFocused = { onFocusedIndex(index) },
                    onLaunch = { onLaunch(app) },
                    onOptions = { onOptions(app) },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: AppEntry,
    width: Dp,
    alwaysShowLabel: Boolean,
    requester: FocusRequester?,
    onFocused: () -> Unit,
    onLaunch: () -> Unit,
    onOptions: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "scale",
    )
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "glow")
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
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .glass(corner, emphasis = glow, rim = false)
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
                    modifier = Modifier.size(height * 0.56f),
                )
            }
            // Glossy top highlight over artwork
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
            // Light-catching rim on top of everything
            Box(Modifier.fillMaxSize().glassRim(corner))
            // Focus ring
            Box(
                Modifier
                    .fillMaxSize()
                    .border(3.dp, Color.White.copy(alpha = 0.95f * glow), shape)
            )
        }

        Spacer(Modifier.height(22.dp))
        GText(
            text = app.label,
            size = 19.sp,
            weight = FontWeight.Medium,
            color = Color.White.copy(alpha = if (alwaysShowLabel) 0.9f else 0.95f * glow),
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
            align = TextAlign.Center,
            shadow = true,
        )
    }
}

@Composable
private fun ClockPill(settings: LauncherSettings) {
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
        GText(time, 32.sp, FontWeight.SemiBold, Color.White)
        Spacer(Modifier.width(16.dp))
        GText(date, 18.sp, FontWeight.Normal, Color.White.copy(alpha = 0.75f))
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
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
        // "sliders" icon, drawn by hand so we don't need an icon library
        Canvas(Modifier.size(28.dp)) {
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
    }
}
