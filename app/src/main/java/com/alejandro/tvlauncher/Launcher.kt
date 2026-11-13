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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
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
    tvListingsGranted: Boolean,
    homeStatus: String,
    onMakeDefault: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onLaunchContent: (ContentItem) -> Unit,
    onOptions: (AppEntry) -> Unit,
    onPickWallpaper: () -> Unit,
    onRequestLocation: () -> Unit,
    onRequestTvListings: () -> Unit,
    onSystemIntent: (String) -> Unit,
    onLanguageChanged: () -> Unit,
) {
    val context = LocalContext.current

    // Weak GPUs (older Mali etc.) get a lighter look. Detected once, off the main thread.
    val gpu by produceState<String?>(null) { value = withContext(Dispatchers.Default) { GpuInfo.renderer() } }
    val lite = when (settings.performance) {
        1 -> true
        2 -> false
        else -> GpuInfo.isWeakGpu(gpu)
    }

    // Slideshow: cycles through the available wallpapers without overwriting the one you picked.
    var slideId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.slideshow, settings.wallpaperRev) {
        slideId = null
        if (settings.slideshow <= 0) return@LaunchedEffect
        while (true) {
            delay(settings.slideshow * 60_000L)
            val ids = Wallpapers.cycleIds(context)
            val current = slideId ?: settings.wallpaper
            slideId = ids[(ids.indexOf(current) + 1) % ids.size]
        }
    }
    val wallpaperId = if (settings.slideshow > 0) slideId ?: settings.wallpaper else settings.wallpaper

    // "Daily photo" wallpaper: fetch today's picture whenever it's selected or Home is pressed.
    LaunchedEffect(settings.wallpaper, homeSignal) {
        if (settings.wallpaper == Wallpapers.DAILY && Wallpapers.refreshDaily(context)) {
            settings.wallpaperRev += 1
        }
    }

    // Decodes + blurs off the main thread; keeps showing the old wallpaper until the new one is ready.
    val wallpaper by produceState<Wallpapers.Loaded?>(null, wallpaperId, settings.wallpaperRev) {
        val loaded = runCatching { Wallpapers.load(context, wallpaperId) }.getOrNull()
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

    // Built-in mini apps (Live TV, Movies & TV): tiles at the start of the row that open inside the launcher.
    val minis = remember { MiniApps.entries(context) }
    var miniApp by remember { mutableStateOf<String?>(null) }
    var pendingMovie by remember { mutableStateOf<TmdbTitle?>(null) }

    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showQuick by remember { mutableStateOf(false) }
    var menuApp by remember { mutableStateOf<AppEntry?>(null) }
    var focusedPackage by remember { mutableStateOf<String?>(null) }
    // Which tile gets focus when Home comes back after an overlay (null = the first one).
    var restoreTo by remember { mutableStateOf<String?>(null) }
    var rootSize by remember { mutableStateOf(IntSize(1920, 1080)) }

    LaunchedEffect(homeSignal) {
        if (homeSignal > 0) {
            showSettings = false
            showSearch = false
            showQuick = false
            menuApp = null
            miniApp = null
            restoreTo = null
        }
    }
    LaunchedEffect(settingsSignal) { if (settingsSignal > 0) showSettings = true }

    // HDMI / composite inputs, shown as tiles after the apps.
    val inputs by produceState<List<AppEntry>>(emptyList(), settings.showInputs, homeSignal) {
        value = if (settings.showInputs) InputRepo.load(context) else emptyList()
    }

    // Apps in display order: pinned first, then custom order or recently used.
    val arranged = remember(
        allApps, settings.hidden, settings.showNonTv, settings.pinned, settings.order,
        settings.sortMode, settings.launched,
    ) {
        settings.arrange(allApps.filter {
            it.packageName !in settings.hidden &&
                it.packageName != MiniApps.MUSIC_PACKAGE &&
                (settings.showNonTv || it.isTv)
        })
    }
    val visible = remember(arranged, inputs, minis, settings.hidden) {
        minis.filter { it.packageName !in settings.hidden } + arranged + inputs
    }
    val focused = visible.firstOrNull { it.packageName == focusedPackage } ?: visible.firstOrNull()

    fun closeOverlay() {
        restoreTo = focusedPackage
        menuApp = null
        showSearch = false
        showQuick = false
    }

    // Titles the focused app has published (e.g. Netflix "Continue watching"). Waits a moment so
    // scrolling quickly across the tiles doesn't fire a query per tile.
    val focusedPkg = focused?.takeIf { !it.isInput && !MiniApps.isMini(it) }?.packageName
    val content by produceState<List<ContentItem>>(
        emptyList(), focusedPkg, settings.showContent, tvListingsGranted
    ) {
        if (!settings.showContent || focusedPkg == null) {
            value = emptyList()
            return@produceState
        }
        delay(250)
        value = ContentRepo.load(context, focusedPkg)
    }

    // A lightweight public row for the launcher homepage. It refreshes when Home is pressed again.
    val trendingMovies by produceState<List<TmdbTitle>>(emptyList(), homeSignal) {
        value = Tmdb.trendingMovies().take(12)
    }

    val accent by animateColorAsState(
        targetValue = focused?.accent ?: Color(0xFF2A2A44),
        animationSpec = tween(if (lite) 300 else 700),
        label = "accent",
    )
    val style = GlassStyle.entries[settings.glass.coerceIn(0, GlassStyle.entries.size - 1)]

    CompositionLocalProvider(
        LocalGlass provides GlassEnv(
            blurred = wallpaper?.blurred,
            width = rootSize.width.toFloat(),
            height = rootSize.height.toFloat(),
            style = style,
            lite = lite,
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF07070C))
                .onSizeChanged { rootSize = it }
        ) {
            // 1. wallpaper (a crossfade keeps two full-screen images alive, so lite mode just swaps)
            if (lite) {
                val img = wallpaper?.full
                if (img != null) {
                    Image(
                        bitmap = img,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Low,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
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
            }

            // 2-4. Dimming, the focused app's colour and the bottom scrim used to be three
            // full-screen layers. They are now one draw pass, and the animated accent colour is read
            // while drawing, so a focus change redraws this layer instead of recomposing the screen.
            val dim = settings.dim
            val tint = settings.accentTint
            Box(
                Modifier.fillMaxSize().drawBehind {
                    if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
                    if (tint) {
                        drawRect(
                            Brush.verticalGradient(
                                0f to accent.copy(alpha = 0.50f),
                                0.65f to Color.Transparent,
                            )
                        )
                    }
                    // scrim so text at the bottom stays readable on any wallpaper
                    drawRect(
                        Brush.verticalGradient(
                            0.40f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f),
                        )
                    )
                }
            )

            val menu = menuApp
            if (miniApp == MiniApps.LIVE) {
                LiveTvApp(
                    settings = settings,
                    lite = lite,
                    onClose = {
                        restoreTo = MiniApps.LIVE
                        miniApp = null
                    },
                )
            } else if (miniApp == MiniApps.MOVIES) {
                MoviesApp(
                    settings = settings,
                    initialTitle = pendingMovie,
                    lite = lite,
                    onClose = {
                        restoreTo = MiniApps.MOVIES
                        pendingMovie = null
                        miniApp = null
                    },
                )
            } else if (miniApp == MiniApps.MUSIC) {
                MusicApp(
                    onClose = {
                        restoreTo = MiniApps.MUSIC
                        miniApp = null
                    },
                )
            } else if (showSettings) {
                SettingsScreen(
                    settings = settings,
                    // mini apps are included so they can be un-hidden from "Hidden apps"
                    allApps = minis + allApps,
                    locationGranted = locationGranted,
                    tvListingsGranted = tvListingsGranted,
                    gpuName = gpu,
                    lite = lite,
                    homeStatus = homeStatus,
                    onMakeDefault = onMakeDefault,
                    onClose = {
                        restoreTo = focusedPackage
                        showSettings = false
                    },
                    onPickWallpaper = onPickWallpaper,
                    onRequestLocation = onRequestLocation,
                    onRequestTvListings = onRequestTvListings,
                    onSystemIntent = onSystemIntent,
                    onLanguageChanged = onLanguageChanged,
                )
            } else if (showSearch) {
                SearchScreen(
                    apps = arranged,
                    tvListingsGranted = tvListingsGranted,
                    lite = lite,
                    onLaunchApp = { app ->
                        onLaunch(app)
                        settings.recordLaunch(app.packageName)
                    },
                    onLaunchContent = { item ->
                        onLaunchContent(item)
                        settings.recordLaunch(item.packageName)
                    },
                    onClose = ::closeOverlay,
                )
            } else if (showQuick) {
                QuickSettingsPanel(
                    net = net,
                    onSystemIntent = onSystemIntent,
                    onOpenLauncherSettings = {
                        showQuick = false
                        showSettings = true
                    },
                    onClose = ::closeOverlay,
                )
            } else if (menu != null) {
                AppMenu(
                    app = menu,
                    settings = settings,
                    arranged = arranged.map { it.packageName },
                    onAppInfo = { onOptions(menu) },
                    onClose = ::closeOverlay,
                )
            } else {
                HomeContent(
                    apps = visible,
                    focused = focused,
                    content = content,
                    trendingMovies = trendingMovies,
                    settings = settings,
                    weather = weather,
                    net = net,
                    homeSignal = homeSignal,
                    restoreTo = restoreTo,
                    onFocusedApp = { focusedPackage = it },
                    onLaunch = { app ->
                        if (MiniApps.isMini(app)) {
                            miniApp = app.packageName
                        } else {
                            onLaunch(app)
                            if (!app.isInput) settings.recordLaunch(app.packageName)
                        }
                    },
                    onLaunchContent = { item ->
                        onLaunchContent(item)
                        settings.recordLaunch(item.packageName)
                    },
                    onOpenTrendingMovie = { title ->
                        pendingMovie = title
                        miniApp = MiniApps.MOVIES
                    },
                    // Long-press OK: pin / move / hide / app info (inputs have none of those)
                    // (mini apps have no menu: hide / show them under Settings > Hidden apps)
                    onOptions = { app -> if (!app.isInput && !MiniApps.isMini(app)) menuApp = app },
                    onOpenSearch = { showSearch = true },
                    onOpenQuick = { showQuick = true },
                    onOpenSettings = { showSettings = true },
                )
            }
        }
    }
}
