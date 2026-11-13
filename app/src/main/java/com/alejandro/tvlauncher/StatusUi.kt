package com.alejandro.tvlauncher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WeatherPill(w: WeatherInfo, compact: Boolean, modifier: Modifier = Modifier) {
    val label = stringResource(w.labelRes)
    val place = if (w.cityIndex > 0) {
        stringResource(WeatherRepo.cities[w.cityIndex.coerceIn(0, WeatherRepo.cities.size - 1)].nameRes)
    } else {
        w.place
    }
    val highLow = stringResource(R.string.wx_hi_lo, w.high, w.low)
    Row(
        modifier.glass(28.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WeatherIcon(w.kind, w.night, Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        GText("${w.temp}${w.unit}", 28.sp, FontWeight.SemiBold, Color.White, maxLines = 1)
        if (!compact) {
            Spacer(Modifier.width(14.dp))
            Column {
                GText(
                    if (place.isBlank()) label else place,
                    16.sp, FontWeight.SemiBold, Color.White, maxLines = 1,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                GText(
                    "$label  ·  $highLow",
                    13.sp, FontWeight.Normal, Color.White.copy(alpha = 0.7f), maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun NetworkPill(n: NetInfo, compact: Boolean, modifier: Modifier = Modifier) {
    val title = when (n.kind) {
        NetKind.Wifi -> n.name ?: stringResource(R.string.net_wifi)
        NetKind.Ethernet -> stringResource(R.string.net_ethernet)
        NetKind.Offline -> stringResource(R.string.net_offline)
        NetKind.Other -> n.name ?: stringResource(R.string.net_connected)
    }
    Row(
        modifier.glass(28.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetIcon(n.kind, n.level, Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            GText(
                title, 18.sp, FontWeight.SemiBold, Color.White, maxLines = 1,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            if (!compact && n.ip != null) {
                GText(n.ip, 13.sp, FontWeight.Normal, Color.White.copy(alpha = 0.7f), maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------ icons (hand drawn)

@Composable
private fun NetIcon(kind: NetKind, level: Int, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (kind) {
            NetKind.Wifi, NetKind.Offline -> {
                // Lucide "wifi" icon (24-unit grid): a dot and three arcs, all centred on (12, 20).
                // The signal level lights 1-3 arcs; the rest stay dim.
                val u = w / 24f
                val lit = if (kind == NetKind.Offline) 0 else ((level.coerceAtLeast(1) * 3 + 3) / 4)
                val on = Color.White.copy(alpha = 0.95f)
                val off = Color.White.copy(alpha = 0.28f)
                val stroke = Stroke(width = 2.5f * u, cap = StrokeCap.Round)
                val arcs = listOf(5f to 3.5f, 10f to 7f, 15f to 10f) // radius to half-chord
                arcs.forEachIndexed { i, (r, c) ->
                    val d = kotlin.math.sqrt(r * r - c * c)
                    val phi = Math.toDegrees(kotlin.math.atan2(d.toDouble(), c.toDouble())).toFloat()
                    drawArc(
                        color = if (i < lit) on else off,
                        startAngle = 180f + phi,
                        sweepAngle = 180f - 2f * phi,
                        useCenter = false,
                        topLeft = Offset((12f - r) * u, (20f - r) * u),
                        size = Size(2f * r * u, 2f * r * u),
                        style = stroke,
                    )
                }
                drawCircle(color = if (kind == NetKind.Offline) off else on, radius = 1.25f * u, center = Offset(12f * u, 20f * u))
                if (kind == NetKind.Offline) {
                    drawLine(
                        color = Color(0xFFFF6B6B),
                        start = Offset(0f, 0f),
                        end = Offset(w, h),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            else -> {
                // wired / other: a little network port
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.95f),
                    topLeft = Offset(w * 0.08f, h * 0.22f),
                    size = Size(w * 0.84f, h * 0.56f),
                    cornerRadius = CornerRadius(w * 0.08f),
                    style = Stroke(width = 2.5.dp.toPx()),
                )
                for (i in 0 until 4) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.95f),
                        topLeft = Offset(w * (0.25f + 0.16f * i), h * 0.34f),
                        size = Size(w * 0.07f, h * 0.2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherIcon(kind: WeatherKind, night: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val cloudColor = Color.White.copy(alpha = 0.95f)
        when (kind) {
            WeatherKind.Clear ->
                if (night) moon(Offset(s * 0.5f, s * 0.5f), s * 0.30f)
                else sun(Offset(s * 0.5f, s * 0.5f), s * 0.20f)

            WeatherKind.PartlyCloudy -> {
                if (night) moon(Offset(s * 0.36f, s * 0.36f), s * 0.20f)
                else sun(Offset(s * 0.36f, s * 0.36f), s * 0.14f)
                cloud(s * 0.12f, s * 0.26f, s * 0.90f, cloudColor)
            }

            WeatherKind.Cloudy -> cloud(s * 0.03f, s * 0.12f, s * 0.95f, cloudColor)

            WeatherKind.Fog -> {
                val ys = listOf(0.32f, 0.50f, 0.68f)
                val xs = listOf(0.15f to 0.85f, 0.25f to 0.95f, 0.10f to 0.75f)
                ys.forEachIndexed { i, y ->
                    drawLine(
                        color = cloudColor,
                        start = Offset(s * xs[i].first, s * y),
                        end = Offset(s * xs[i].second, s * y),
                        strokeWidth = s * 0.08f,
                        cap = StrokeCap.Round,
                    )
                }
            }

            WeatherKind.Rain -> {
                cloud(s * 0.03f, 0f, s * 0.95f, cloudColor)
                for (i in 0 until 3) {
                    val x = s * (0.32f + 0.18f * i)
                    drawLine(
                        color = Color(0xFF7FC8FF),
                        start = Offset(x, s * 0.72f),
                        end = Offset(x - s * 0.06f, s * 0.92f),
                        strokeWidth = s * 0.06f,
                        cap = StrokeCap.Round,
                    )
                }
            }

            WeatherKind.Snow -> {
                cloud(s * 0.03f, 0f, s * 0.95f, cloudColor)
                for (i in 0 until 3) {
                    drawCircle(
                        color = Color.White,
                        radius = s * 0.045f,
                        center = Offset(s * (0.32f + 0.18f * i), s * (0.80f + 0.06f * (i % 2))),
                    )
                }
            }

            WeatherKind.Storm -> {
                cloud(s * 0.03f, 0f, s * 0.95f, Color(0xFFD5DAE6))
                val bolt = Path().apply {
                    moveTo(s * 0.54f, s * 0.60f)
                    lineTo(s * 0.40f, s * 0.82f)
                    lineTo(s * 0.51f, s * 0.82f)
                    lineTo(s * 0.45f, s * 0.99f)
                    lineTo(s * 0.66f, s * 0.74f)
                    lineTo(s * 0.54f, s * 0.74f)
                    close()
                }
                drawPath(bolt, Color(0xFFFFD54F))
            }
        }
    }
}

private fun DrawScope.sun(center: Offset, r: Float) {
    val col = Color(0xFFFFD54F)
    drawCircle(col, radius = r, center = center)
    for (i in 0 until 8) {
        val a = (i * PI / 4).toFloat()
        val dx = cos(a)
        val dy = sin(a)
        drawLine(
            color = col,
            start = Offset(center.x + dx * r * 1.45f, center.y + dy * r * 1.45f),
            end = Offset(center.x + dx * r * 1.85f, center.y + dy * r * 1.85f),
            strokeWidth = r * 0.28f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.moon(center: Offset, r: Float) {
    drawCircle(Color(0xFFE8ECF5), radius = r, center = center)
    drawCircle(Color(0xFFC5CBDB), radius = r * 0.22f, center = Offset(center.x - r * 0.35f, center.y - r * 0.20f))
    drawCircle(Color(0xFFC5CBDB), radius = r * 0.15f, center = Offset(center.x + r * 0.30f, center.y + r * 0.30f))
}

/** A cloud that fits in a square of side [u], with its top-left corner at ([ox], [oy]). */
private fun DrawScope.cloud(ox: Float, oy: Float, u: Float, color: Color) {
    drawCircle(color, radius = u * 0.20f, center = Offset(ox + u * 0.32f, oy + u * 0.58f))
    drawCircle(color, radius = u * 0.26f, center = Offset(ox + u * 0.52f, oy + u * 0.44f))
    drawCircle(color, radius = u * 0.19f, center = Offset(ox + u * 0.74f, oy + u * 0.58f))
    drawRoundRect(
        color = color,
        topLeft = Offset(ox + u * 0.14f, oy + u * 0.56f),
        size = Size(u * 0.74f, u * 0.22f),
        cornerRadius = CornerRadius(u * 0.11f),
    )
}
