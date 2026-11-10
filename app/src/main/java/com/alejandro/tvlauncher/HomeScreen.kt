package com.alejandro.tvlauncher

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TileSize = 176.dp
private val TileShape = RoundedCornerShape(30.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    apps: List<AppEntry>,
    homeSignal: Int,
    onLaunch: (AppEntry) -> Unit,
    onOptions: (AppEntry) -> Unit,
) {
    // A launcher must never "go back" to anything.
    BackHandler(enabled = true) { }

    var focusedIndex by remember { mutableIntStateOf(0) }
    val focused = apps.getOrNull(focusedIndex)
    val accent by animateColorAsState(
        targetValue = focused?.accent ?: Color(0xFF2A2A38),
        animationSpec = tween(700),
        label = "accent",
    )

    val listState = rememberLazyListState()
    val firstTile = remember { FocusRequester() }

    // Grab focus once the list is on screen, and again whenever Home is pressed.
    LaunchedEffect(apps.isNotEmpty(), homeSignal) {
        if (apps.isNotEmpty()) {
            listState.animateScrollToItem(0)
            runCatching { firstTile.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to accent.copy(alpha = 0.95f),
                    0.55f to Color(0xFF0A0A0F),
                    1f to Color.Black,
                )
            )
    ) {
        // Soft light bloom, top right – gives the flat gradient some depth.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(1600f, 0f),
                        radius = 1100f,
                    )
                )
        )

        Clock(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 72.dp))

        // Hero text for the focused app
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 96.dp, bottom = 120.dp)
                .fillMaxWidth(0.6f)
        ) {
            if (focused == null) {
                Text("Loading apps…", 28.sp, FontWeight.Medium, Color.White.copy(alpha = 0.6f))
            } else {
                AnimatedContent(
                    targetState = focused.label,
                    transitionSpec = {
                        (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 6 }) togetherWith
                            (fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 6 })
                    },
                    label = "title",
                ) { title ->
                    Text(title, 64.sp, FontWeight.Bold, Color.White, maxLines = 2)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Press OK to open  ·  Hold OK for app info",
                    20.sp, FontWeight.Normal, Color.White.copy(alpha = 0.55f),
                )
            }
        }

        // The app row
        LazyRow(
            state = listState,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 48.dp),
            contentPadding = PaddingValues(horizontal = 96.dp, vertical = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(34.dp),
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                AppTile(
                    app = app,
                    requester = if (index == 0) firstTile else null,
                    onFocused = { focusedIndex = index },
                    onLaunch = { onLaunch(app) },
                    onOptions = { onOptions(app) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: AppEntry,
    requester: FocusRequester?,
    onFocused: () -> Unit,
    onLaunch: () -> Unit,
    onOptions: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.18f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "scale",
    )
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "glow")

    Column(
        modifier = Modifier.width(TileSize),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(TileSize)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(
                    elevation = (6 + 26 * glow).dp,
                    shape = TileShape,
                    ambientColor = app.accent,
                    spotColor = app.accent,
                )
                .clip(TileShape)
                .background(Brush.linearGradient(listOf(app.accent, Color(0xFF101016))))
                .border(3.dp, Color.White.copy(alpha = 0.9f * glow), TileShape)
                // Order matters: focus observers must sit before the focusable (clickable).
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .combinedClickable(onClick = onLaunch, onLongClick = onOptions)
        ) {
            Image(
                bitmap = app.icon,
                contentDescription = app.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
            // Glossy highlight across the top half
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TileSize / 2)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.16f), Color.Transparent)
                        )
                    )
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = app.label,
            size = 19.sp,
            weight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.95f * glow),
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
            align = TextAlign.Center,
        )
    }
}

@Composable
private fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000L * (60 - (System.currentTimeMillis() / 1000 % 60)))
        }
    }
    val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val time = SimpleDateFormat(timePattern, Locale.getDefault()).format(now)
    val date = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now)

    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(time, 40.sp, FontWeight.SemiBold, Color.White)
        Text(date, 18.sp, FontWeight.Normal, Color.White.copy(alpha = 0.6f))
    }
}

/** Small wrapper so call sites stay readable. */
@Composable
private fun Text(
    text: String,
    size: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = androidx.compose.ui.text.TextStyle(
            fontSize = size,
            fontWeight = weight,
            color = color,
            textAlign = align ?: TextAlign.Start,
        ),
    )
}
