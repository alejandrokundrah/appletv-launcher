package com.alejandro.tvlauncher

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Root of the launcher: wallpaper + tint layers behind, then either the home screen or settings.
 * Everything glass-like reads the pre-blurred wallpaper from [LocalGlass].
 */
@Composable
fun LauncherRoot(
    allApps: List<AppEntry>,
    settings: LauncherSettings,
    homeSignal: Int,
    settingsSignal: Int,
    locationGranted: Boolean,
    homeStatus: String,
    onMakeDefault: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onOptions: (AppEntry) -> Unit,
    onPickWallpaper: () -> Unit,
    onRequestLocation: () -> Unit,
    onSystemIntent: (String) -> Unit,
) {
    val context = LocalContext.current

    // Decodes + blurs off the main thread; keeps showing the old wallpaper until the new one is ready.
    val wallpaper by produceState<Wallpapers.Loaded?>(null, settings.wallpaper, settings.wallpaperRev) {
        val loaded = runCatching { Wallpapers.load(context, settings.wallpaper) }.getOrNull()
        if (loaded != null) value = loaded
    }

    // Weather: refreshed every 30 min (retried every minute until the first success).
    val weather by produceState<WeatherInfo?>(
        null, settings.showWeather, settings.weatherCity, settings.fahrenheit
    ) {
        if (!settings.showWeather) {
            value = null
            return@produceState
        }
        while (true) {
            val w = WeatherRepo.fetch(settings.weatherCity, settings.fahrenheit)
            if (w != null) value = w
            delay(if (w != null) 30 * 60_000L else 60_000L)
        }
    }

    // Network: re-read every few seconds so signal strength and reconnects show up.
    val net by produceState<NetInfo?>(null, settings.showNetwork, locationGranted) {
        if (!settings.showNetwork) {
            value = null
            return@produceState
        }
        while (true) {
            val n = withContext(Dispatchers.IO) { runCatching { NetStatus.read(context) }.getOrNull() }
            if (n != null) value = n
            delay(5_000L)
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var rootSize by remember { mutableStateOf(IntSize(1920, 1080)) }

    LaunchedEffect(homeSignal) { if (homeSignal > 0) showSettings = false }
    LaunchedEffect(settingsSignal) { if (settingsSignal > 0) showSettings = true }

    val visible = remember(allApps, settings.hidden, settings.showNonTv) {
        allApps.filter { it.packageName !in settings.hidden && (settings.showNonTv || it.isTv) }
    }
    val safeIndex = focusedIndex.coerceIn(0, (visible.size - 1).coerceAtLeast(0))
    val focused = visible.getOrNull(safeIndex)

    val accent by animateColorAsState(
        targetValue = focused?.accent ?: Color(0xFF2A2A44),
        animationSpec = tween(700),
        label = "accent",
    )
    val style = GlassStyle.entries[settings.glass.coerceIn(0, GlassStyle.entries.size - 1)]

    CompositionLocalProvider(
        LocalGlass provides GlassEnv(
            blurred = wallpaper?.blurred,
            width = rootSize.width.toFloat(),
            height = rootSize.height.toFloat(),
            style = style,
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF07070C))
                .onSizeChanged { rootSize = it }
        ) {
            // 1. wallpaper
            Crossfade(
                targetState = wallpaper?.full,
                animationSpec = tween(700),
                label = "wallpaper",
            ) { img ->
                if (img != null) {
                    Image(
                        bitmap = img,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize())
                }
            }

            // 2. dimming
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.dim)))

            // 3. colour from the focused app
            if (settings.accentTint) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to accent.copy(alpha = 0.50f),
                            0.65f to Color.Transparent,
                        )
                    )
                )
            }

            // 4. scrim so text at the bottom stays readable on any wallpaper
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.40f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.55f),
                    )
                )
            )

            if (showSettings) {
                SettingsScreen(
                    settings = settings,
                    allApps = allApps,
                    locationGranted = locationGranted,
                    homeStatus = homeStatus,
                    onMakeDefault = onMakeDefault,
                    onClose = { showSettings = false },
                    onPickWallpaper = onPickWallpaper,
                    onRequestLocation = onRequestLocation,
                    onSystemIntent = onSystemIntent,
                )
            } else {
                HomeContent(
                    apps = visible,
                    focused = focused,
                    settings = settings,
                    weather = weather,
                    net = net,
                    homeSignal = homeSignal,
                    onFocusedIndex = { focusedIndex = it },
                    onLaunch = onLaunch,
                    onOptions = onOptions,
                    onOpenSettings = { showSettings = true },
                )
            }
        }
    }
}
