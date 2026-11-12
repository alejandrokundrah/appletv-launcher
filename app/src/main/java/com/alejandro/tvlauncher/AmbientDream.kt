package com.alejandro.tvlauncher

import android.content.Context
import android.service.dreams.DreamService
import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The launcher's screensaver. Pick it in Android's screensaver settings (Settings > Screensaver, or the
 * "Screensaver" row in the launcher settings). It slowly cycles through the launcher's wallpapers with the
 * time and weather moving between corners so nothing burns into the screen.
 */
class AmbientDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {

    // A DreamService is not an Activity, so it has to provide the lifecycle / saved-state owners Compose needs.
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val decor = window.decorView
        decor.setViewTreeLifecycleOwner(this)
        decor.setViewTreeSavedStateRegistryOwner(this)
        setContentView(ComposeView(this).apply { setContent { AmbientScreen(applicationContext) } })
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }
}

private const val SLIDE_MS = 14_000L

@Composable
private fun AmbientScreen(context: Context) {
    val settings = remember { LauncherSettings(context) }
    val ids = remember { Wallpapers.cycleIds(context) }
    var index by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(SLIDE_MS)
            index++
        }
    }

    // The next picture is decoded in the background; the previous one stays up until it is ready.
    val image by produceState<ImageBitmap?>(null, index) {
        val img = runCatching { Wallpapers.loadFull(context, ids[index % ids.size]) }.getOrNull()
        if (img != null) value = img
    }

    val weather by produceState<WeatherInfo?>(null) {
        if (settings.showWeather) value = WeatherRepo.fetch(settings.weatherCity, settings.fahrenheit)
    }

    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000L * (60 - (System.currentTimeMillis() / 1000 % 60)))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = image, animationSpec = tween(2000), label = "dream") { img ->
            if (img != null) {
                // Each picture zooms slowly while it's on screen (a gentle "Ken Burns" drift).
                val zoom = remember { Animatable(1f) }
                LaunchedEffect(Unit) { zoom.animateTo(1.10f, tween((SLIDE_MS * 1.3).toInt(), easing = LinearEasing)) }
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom.value
                            scaleY = zoom.value
                        },
                )
            }
        }

        // Darken the bottom so the text stays readable on bright pictures.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))
            )
        )

        // The clock hops between the four corners every slide to avoid screen burn-in.
        val corners = remember {
            listOf(Alignment.BottomStart, Alignment.TopEnd, Alignment.BottomEnd, Alignment.TopStart)
        }
        Crossfade(targetState = index % corners.size, animationSpec = tween(1500), label = "corner") { pos ->
            Box(Modifier.fillMaxSize().padding(72.dp), contentAlignment = corners[pos]) {
                ClockBlock(settings, context, now, weather)
            }
        }
    }
}

@Composable
private fun ClockBlock(settings: LauncherSettings, context: Context, now: Date, weather: WeatherInfo?) {
    val pattern = when (settings.clock) {
        1 -> "h:mm"
        2 -> "HH:mm"
        else -> if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    }
    val time = SimpleDateFormat(pattern, Locale.getDefault()).format(now)
    val date = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now)
    Column {
        GText(time, 104.sp, FontWeight.Light, Color.White, shadow = true)
        GText(date, 28.sp, FontWeight.Normal, Color.White.copy(alpha = 0.85f), shadow = true)
        if (weather != null) {
            GText(
                "${weather.temp}°  ${stringResource(weather.labelRes)}",
                26.sp, FontWeight.Normal, Color.White.copy(alpha = 0.75f), shadow = true,
            )
        }
    }
}
