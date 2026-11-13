package com.alejandro.tvlauncher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Dark layer drawn over the wallpaper behind the mini apps, so lists stay readable. */
internal val MiniScrim = Color.Black.copy(alpha = 0.74f)

/** Pill-shaped button / chip: tabs, categories, seasons, Play. */
@Composable
internal fun MiniButton(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    primary: Boolean = false,
    requester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val e by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "mini-button")
    val shape = RoundedCornerShape(50)
    val bg = if (primary) {
        Color.White.copy(alpha = 0.80f + 0.20f * e)
    } else {
        Color.White.copy(alpha = (if (selected) 0.26f else 0.10f) + 0.20f * e)
    }
    Box(
        modifier
            .graphicsLayer {
                val s = 1f + 0.06f * e
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(bg)
            .border(2.dp, Color.White.copy(alpha = 0.9f * e), shape)
            .onFocusChanged { focused = it.isFocused }
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        GText(
            text, 20.sp,
            if (selected || primary) FontWeight.SemiBold else FontWeight.Medium,
            if (primary) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

/** Search box. Press OK on the remote to bring the on-screen keyboard back after it was dismissed. */
@Composable
internal fun MiniSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = if (focused) 0.22f else 0.10f))
            .border(2.dp, Color.White.copy(alpha = if (focused) 0.9f else 0f), shape)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 22.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                focusManager.moveFocus(FocusDirection.Down)
            }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
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
                    if (value.isEmpty()) {
                        GText(hint, 22.sp, FontWeight.Normal, Color.White.copy(alpha = 0.45f), maxLines = 1)
                    }
                    inner()
                }
            },
        )
    }
}

/** Centred status text ("Loading…", "No results"). */
@Composable
internal fun MiniMessage(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        GText(text, 24.sp, FontWeight.Normal, Color.White.copy(alpha = 0.7f), align = TextAlign.Center)
    }
}

/** Artwork card (poster, episode still): focus zoom + ring, title and optional subtitle underneath. */
@Composable
internal fun MiniCard(
    title: String,
    imageUrl: String?,
    w: Dp,
    h: Dp,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    requester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, imageUrl) {
        value = imageUrl?.let { PosterLoader.load(context, it) }
    }
    var focused by remember { mutableStateOf(false) }
    val e by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "mini-card")
    val shape = RoundedCornerShape(14.dp)

    Column(modifier.width(w)) {
        Box(
            modifier = Modifier
                .size(w, h)
                .graphicsLayer {
                    val s = 1f + 0.07f * e
                    scaleX = s
                    scaleY = s
                }
                .clip(shape)
                .background(Color.White.copy(alpha = 0.10f))
                .border(3.dp, Color.White.copy(alpha = 0.95f * e), shape)
                .onFocusChanged { focused = it.isFocused }
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val img = image
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                GText(
                    title, 16.sp, FontWeight.Medium, Color.White.copy(alpha = 0.9f),
                    maxLines = 4, align = TextAlign.Center,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        GText(
            title, 15.sp, FontWeight.Normal, Color.White.copy(alpha = 0.88f),
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
