package com.alejandro.tvlauncher

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private enum class Page { Main, Hidden }

@Composable
fun SettingsScreen(
    settings: LauncherSettings,
    allApps: List<AppEntry>,
    onClose: () -> Unit,
    onPickWallpaper: () -> Unit,
    onSystemIntent: (String) -> Unit,
) {
    var page by remember { mutableStateOf(Page.Main) }
    val first = remember(page) { FocusRequester() }

    BackHandler(enabled = true) {
        if (page == Page.Hidden) page = Page.Main else onClose()
    }

    LaunchedEffect(page) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.58f)
                .fillMaxHeight(0.88f)
                .glass(38.dp, darken = 0.22f)
                .padding(horizontal = 36.dp, vertical = 30.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GText(
                    if (page == Page.Main) "Settings" else "Hidden apps",
                    36.sp, FontWeight.Bold, Color.White,
                )
                Spacer(Modifier.weight(1f))
                GText("Back to close", 17.sp, FontWeight.Normal, Color.White.copy(alpha = 0.55f))
            }
            Spacer(Modifier.height(16.dp))

            if (page == Page.Main) {
                MainPage(settings, first, onPickWallpaper, onSystemIntent, onOpenHidden = { page = Page.Hidden })
            } else {
                HiddenPage(settings, allApps, first)
            }
        }
    }
}

@Composable
private fun MainPage(
    settings: LauncherSettings,
    first: FocusRequester,
    onPickWallpaper: () -> Unit,
    onSystemIntent: (String) -> Unit,
    onOpenHidden: () -> Unit,
) {
    val glassCount = GlassStyle.entries.size

    LazyColumn(
        Modifier.fillMaxWidth().fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { SectionHeader("APPEARANCE") }

        item {
            SettingRow(
                title = "Glass style",
                value = GlassStyle.entries[settings.glass.coerceIn(0, glassCount - 1)].label,
                requester = first,
                onLeft = { settings.glass = (settings.glass + glassCount - 1) % glassCount },
                onRight = { settings.glass = (settings.glass + 1) % glassCount },
            )
        }
        item { WallpaperRow(settings, onPickWallpaper) }
        item {
            SettingRow(
                title = "Wallpaper dimming",
                value = "${(settings.dim * 100).roundToInt()}%",
                onLeft = { settings.dim = (settings.dim - 0.05f).coerceAtLeast(0f) },
                onRight = { settings.dim = (settings.dim + 0.05f).coerceAtMost(0.7f) },
            )
        }
        item {
            SettingRow(
                title = "Tile size",
                value = listOf("Small", "Medium", "Large")[settings.tileSize.coerceIn(0, 2)],
                onLeft = { settings.tileSize = (settings.tileSize + 2) % 3 },
                onRight = { settings.tileSize = (settings.tileSize + 1) % 3 },
            )
        }
        item {
            SettingRow(
                title = "App names",
                value = if (settings.labels == 1) "Always" else "Focused only",
                onLeft = { settings.labels = 1 - settings.labels },
                onRight = { settings.labels = 1 - settings.labels },
            )
        }
        item {
            SettingRow(
                title = "Backdrop glow from focused app",
                value = if (settings.accentTint) "On" else "Off",
                onLeft = { settings.accentTint = !settings.accentTint },
                onRight = { settings.accentTint = !settings.accentTint },
            )
        }

        item { SectionHeader("HOME") }

        item {
            SettingRow(
                title = "Show non-TV apps",
                value = if (settings.showNonTv) "On" else "Off",
                onLeft = { settings.showNonTv = !settings.showNonTv },
                onRight = { settings.showNonTv = !settings.showNonTv },
            )
        }
        item {
            SettingRow(
                title = "Clock format",
                value = listOf("System", "12-hour", "24-hour")[settings.clock.coerceIn(0, 2)],
                onLeft = { settings.clock = (settings.clock + 2) % 3 },
                onRight = { settings.clock = (settings.clock + 1) % 3 },
            )
        }
        item {
            SettingRow(
                title = "Hidden apps",
                value = "${settings.hidden.size} hidden  ›",
                onClick = onOpenHidden,
            )
        }

        item { SectionHeader("SYSTEM") }

        item {
            SettingRow(
                title = "Wi-Fi",
                value = "Open  ›",
                onClick = { onSystemIntent(Settings.ACTION_WIFI_SETTINGS) },
            )
        }
        item {
            SettingRow(
                title = "Android settings",
                value = "Open  ›",
                onClick = { onSystemIntent(Settings.ACTION_SETTINGS) },
            )
        }
        item {
            SettingRow(
                title = "Default home app",
                value = "Open  ›",
                onClick = { onSystemIntent(Settings.ACTION_HOME_SETTINGS) },
            )
        }
        item {
            SettingRow(
                title = "Reset launcher settings",
                value = "Reset",
                onClick = { settings.reset() },
            )
        }
    }
}

@Composable
private fun HiddenPage(settings: LauncherSettings, apps: List<AppEntry>, first: FocusRequester) {
    LazyColumn(
        Modifier.fillMaxWidth().fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(apps, key = { it.packageName }) { app ->
            val hidden = app.packageName in settings.hidden
            SettingRow(
                title = app.label,
                value = if (hidden) "Hidden" else "Visible",
                requester = if (app === apps.firstOrNull()) first else null,
                leading = {
                    Image(
                        bitmap = app.icon,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                },
                onLeft = { settings.toggleHidden(app.packageName) },
                onRight = { settings.toggleHidden(app.packageName) },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    GText(
        text,
        15.sp,
        FontWeight.SemiBold,
        Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 2.dp),
    )
}

/**
 * One row of the settings list. Left/Right on the remote change the value,
 * OK either activates [onClick] or steps the value forward.
 */
@Composable
private fun SettingRow(
    title: String,
    value: String?,
    requester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val e by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "row")
    val shape = RoundedCornerShape(20.dp)
    val stepper = onLeft != null && onRight != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f + 0.17f * e))
            .border(1.5.dp, Color.White.copy(alpha = 0.6f * e), shape)
            // focus observers + key handling must sit before (outside) the clickable
            .onFocusChanged { focused = it.isFocused }
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft && onLeft != null) {
                    onLeft()
                    true
                } else if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight && onRight != null) {
                    onRight()
                    true
                } else {
                    false
                }
            }
            .clickable {
                if (onClick != null) onClick() else if (onRight != null) onRight()
            }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        GText(
            title, 22.sp, FontWeight.Medium, Color.White,
            modifier = Modifier.weight(1f), maxLines = 1,
        )
        if (value != null) {
            val shown = if (focused && stepper) "‹   $value   ›" else value
            GText(shown, 21.sp, FontWeight.Normal, Color.White.copy(alpha = 0.78f), maxLines = 1)
        }
    }
}

@Composable
private fun WallpaperRow(settings: LauncherSettings, onPick: () -> Unit) {
    val context = LocalContext.current
    val hasCustom = remember(settings.wallpaperRev) { Wallpapers.customFile(context).exists() }

    Column(Modifier.fillMaxWidth()) {
        GText(
            "Wallpaper",
            20.sp, FontWeight.Medium, Color.White.copy(alpha = 0.85f),
            modifier = Modifier.padding(start = 6.dp, top = 6.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp),
        ) {
            items(Wallpapers.presets, key = { it.id }) { p ->
                Thumb(
                    selected = settings.wallpaper == p.id,
                    label = p.name,
                    onClick = { settings.wallpaper = p.id },
                ) {
                    Image(
                        painter = painterResource(p.thumb),
                        contentDescription = p.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (hasCustom) {
                item(key = "custom") {
                    Thumb(
                        selected = settings.wallpaper == Wallpapers.CUSTOM,
                        label = "Your photo",
                        onClick = { settings.wallpaper = Wallpapers.CUSTOM },
                    ) {
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.linearGradient(listOf(Color(0xFF3A3A55), Color(0xFF15151F)))
                            )
                        )
                    }
                }
            }
            item(key = "pick") {
                Thumb(selected = false, label = "Choose photo…", onClick = onPick) {
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f)))
                    GText("+", 40.sp, FontWeight.Light, Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun Thumb(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val e by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "thumb")
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .size(width = 168.dp, height = 94.dp)
            .graphicsLayer {
                val s = 1f + 0.08f * e
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(
                width = if (selected) 3.dp else 1.5.dp,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.25f + 0.65f * e),
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
    ) {
        content()
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            GText(label, 15.sp, FontWeight.Medium, Color.White, maxLines = 1)
        }
    }
}
