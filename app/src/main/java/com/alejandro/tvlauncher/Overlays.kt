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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Dimmed backdrop + one centred glass panel. Shared by every overlay below. */
@Composable
private fun OverlayPanel(
    widthFraction: Float,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(widthFraction)
                .glass(34.dp, darken = 0.22f)
                .padding(horizontal = 32.dp, vertical = 28.dp),
            content = content,
        )
    }
}

// ------------------------------------------------------------------------- long-press menu

/**
 * What happens on a long press of OK on a tile: pin, move left / right, hide, app info.
 * Moving keeps the menu open so an app can be moved several places in a row.
 */
@Composable
fun AppMenu(
    app: AppEntry,
    settings: LauncherSettings,
    /** The apps exactly as shown on the home row (without inputs). */
    arranged: List<String>,
    onAppInfo: () -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    BackHandler(enabled = true) { onClose() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    val pkg = app.packageName
    val isPinned = pkg in settings.pinned

    OverlayPanel(0.36f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.width(16.dp))
            GText(app.label, 32.sp, FontWeight.Bold, Color.White, maxLines = 1)
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingRow(
                title = stringResource(if (isPinned) R.string.menu_unpin else R.string.menu_pin),
                value = null,
                requester = first,
                onClick = { settings.togglePinned(pkg) },
            )
            SettingRow(
                title = stringResource(R.string.menu_move_left),
                value = null,
                onClick = { settings.move(pkg, -1, arranged) },
            )
            SettingRow(
                title = stringResource(R.string.menu_move_right),
                value = null,
                onClick = { settings.move(pkg, 1, arranged) },
            )
            SettingRow(
                title = stringResource(R.string.menu_hide),
                value = null,
                onClick = {
                    settings.toggleHidden(pkg)
                    onClose()
                },
            )
            SettingRow(
                title = stringResource(R.string.menu_info),
                value = null,
                onClick = {
                    onAppInfo()
                    onClose()
                },
            )
            SettingRow(
                title = stringResource(R.string.menu_close),
                value = null,
                onClick = onClose,
            )
        }
    }
}

// ------------------------------------------------------------------------- quick settings

private class QuickItem(val title: Int, val subtitle: String?, val action: () -> Unit)

/** One-button shortcuts to the system screens people reach for most. */
@Composable
fun QuickSettingsPanel(
    net: NetInfo?,
    onSystemIntent: (String) -> Unit,
    onOpenLauncherSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    BackHandler(enabled = true) { onClose() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    val netText = net?.let { n ->
        when (n.kind) {
            NetKind.Wifi -> n.name ?: stringResource(R.string.net_wifi)
            NetKind.Ethernet -> stringResource(R.string.net_ethernet)
            NetKind.Offline -> stringResource(R.string.net_offline)
            NetKind.Other -> n.name ?: stringResource(R.string.net_connected)
        }
    }

    val items = listOf(
        QuickItem(R.string.net_wifi, netText) { onSystemIntent(Settings.ACTION_WIFI_SETTINGS) },
        QuickItem(R.string.quick_network, null) { onSystemIntent(Settings.ACTION_WIRELESS_SETTINGS) },
        QuickItem(R.string.quick_bluetooth, null) { onSystemIntent(Settings.ACTION_BLUETOOTH_SETTINGS) },
        QuickItem(R.string.quick_display, null) { onSystemIntent(Settings.ACTION_DISPLAY_SETTINGS) },
        QuickItem(R.string.quick_sound, null) { onSystemIntent(Settings.ACTION_SOUND_SETTINGS) },
        QuickItem(R.string.quick_datetime, null) { onSystemIntent(Settings.ACTION_DATE_SETTINGS) },
        QuickItem(R.string.quick_access, null) { onSystemIntent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        QuickItem(R.string.quick_launcher, null) { onOpenLauncherSettings() },
    )

    OverlayPanel(0.62f) {
        GText(stringResource(R.string.quick_title), 36.sp, FontWeight.Bold, Color.White)
        Spacer(Modifier.height(18.dp))
        items.chunked(4).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEachIndexed { i, item ->
                    QuickTile(
                        title = stringResource(item.title),
                        subtitle = item.subtitle,
                        requester = if (rowIndex == 0 && i == 0) first else null,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            item.action()
                            // Launcher settings opens inside the launcher; the others leave it.
                            if (item.title != R.string.quick_launcher) onClose()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickTile(
    title: String,
    subtitle: String?,
    requester: FocusRequester?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val e by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "quick")
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .height(112.dp)
            .graphicsLayer {
                val s = 1f + 0.05f * e
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f + 0.17f * e))
            .border(1.5.dp, Color.White.copy(alpha = 0.6f * e), shape)
            .onFocusChanged { focused = it.isFocused }
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        GText(title, 22.sp, FontWeight.Medium, Color.White, maxLines = 2)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            GText(subtitle, 15.sp, FontWeight.Normal, Color.White.copy(alpha = 0.65f), maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------------- search

/**
 * Search across app names and every title the installed apps have published to Android TV.
 * With nothing typed it shows what you can continue watching across all apps.
 */
@Composable
fun SearchScreen(
    apps: List<AppEntry>,
    tvListingsGranted: Boolean,
    lite: Boolean,
    onLaunchApp: (AppEntry) -> Unit,
    onLaunchContent: (ContentItem) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val fieldFocus = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }

    BackHandler(enabled = true) { onClose() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { fieldFocus.requestFocus() }
        keyboard?.show()
    }

    val all by produceState<List<ContentItem>>(emptyList(), tvListingsGranted) {
        value = ContentRepo.loadAll(context)
    }
    val titles = remember(all, query) { ContentRepo.search(all, query).take(40) }
    val words = remember(query) { query.lowercase().split(' ').filter { it.isNotBlank() } }
    val appMatches = remember(apps, words) {
        if (words.isEmpty()) {
            emptyList()
        } else {
            apps.filter { app -> !app.isInput && words.all { it in app.label.lowercase() } }.take(12)
        }
    }
    val labelFor = remember(apps) { apps.associate { it.packageName to it.label } }

    OverlayPanel(0.84f) {
        GText(stringResource(R.string.search_title), 36.sp, FontWeight.Bold, Color.White)
        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth().glass(26.dp).padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 28.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    focusManager.moveFocus(FocusDirection.Down)
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    // OK on the remote re-opens the keyboard after it was dismissed.
                    .onKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyUp && (ev.key == Key.DirectionCenter || ev.key == Key.Enter)) {
                            keyboard?.show()
                            true
                        } else {
                            false
                        }
                    },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            GText(
                                stringResource(R.string.search_hint), 28.sp, FontWeight.Normal,
                                Color.White.copy(alpha = 0.45f),
                            )
                        }
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.height(14.dp))

        if (appMatches.isNotEmpty()) {
            SearchHeader(stringResource(R.string.search_apps))
            LazyRow(
                contentPadding = PaddingValues(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(appMatches, key = { it.packageName }) { app ->
                    Box(Modifier.width(300.dp)) {
                        SettingRow(
                            title = app.label,
                            value = null,
                            leading = {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                )
                            },
                            onClick = {
                                onClose()
                                onLaunchApp(app)
                            },
                        )
                    }
                }
            }
        }

        if (titles.isNotEmpty()) {
            SearchHeader(
                stringResource(
                    if (query.isBlank()) R.string.row_continue else R.string.search_titles
                )
            )
            val cardW: Dp = 210.dp
            LazyRow(
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(titles, key = { it.packageName + "|" + it.title }) { item ->
                    ContentCard(
                        item = item,
                        w = cardW,
                        h = cardW * 9 / 16,
                        lite = lite,
                        subtitle = labelFor[item.packageName],
                    ) {
                        onClose()
                        onLaunchContent(item)
                    }
                }
            }
        } else if (appMatches.isEmpty()) {
            GText(
                stringResource(
                    when {
                        query.isNotBlank() -> R.string.search_none
                        !tvListingsGranted -> R.string.search_need_permission
                        else -> R.string.search_empty
                    }
                ),
                20.sp, FontWeight.Normal, Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SearchHeader(text: String) {
    GText(
        text, 19.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}
