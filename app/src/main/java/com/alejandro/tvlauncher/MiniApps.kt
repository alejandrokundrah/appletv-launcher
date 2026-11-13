package com.alejandro.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap

/** Endpoints and keys used by the built-in mini apps. */
object MiniConfig {
    /** TMDB v3 API key (artwork + catalogue). */
    const val TMDB_KEY = "6460d5bb91883d90e6316441b8435a7d"

    /** Playlist behind the Live TV tile. */
    const val IPTV_M3U = "https://iptv-org.github.io/iptv/index.m3u"

    /**
     * Movie / TV player. Movies: `{PLAYER_BASE}/movie/{id}?color={hex}`,
     * shows: `{PLAYER_BASE}/tv/{id}/{season}/{episode}?color={hex}`.
     */
    const val PLAYER_BASE = "https://viduki.net/3"

    /** Accent colour (hex, no #) passed to the player. */
    const val PLAYER_COLOR = "0a84ff"
}

/**
 * Built-in "apps" that live inside the launcher itself. They show up as normal tiles at the start of
 * the home row (and can be hidden from the long-press menu); [LauncherRoot] opens them in place
 * instead of starting another Android app.
 */
object MiniApps {

    const val PREFIX = "mini:"
    const val LIVE = "mini:live"
    const val MOVIES = "mini:movies"

    fun isMini(app: AppEntry): Boolean = app.packageName.startsWith(PREFIX)

    fun entries(context: Context): List<AppEntry> {
        val live = banner(0xFFFF6B6B.toInt(), 0xFF8E1B3A.toInt()) { c, p -> drawTv(c, p) }
        val movies = banner(0xFF8B6CFF.toInt(), 0xFF2A1B6E.toInt()) { c, p -> drawPlay(c, p) }
        return listOf(
            entry(context.getString(R.string.mini_live_tv), LIVE, live, Color(0xFFE5484D)),
            entry(context.getString(R.string.mini_movies), MOVIES, movies, Color(0xFF7C5CFF)),
        )
    }

    private fun entry(label: String, pkg: String, banner: Bitmap, accent: Color) = AppEntry(
        label = label,
        packageName = pkg,
        launchIntent = Intent(),
        // the middle square of the banner doubles as the icon (menus, hidden-apps list)
        icon = Bitmap.createBitmap(banner, 96, 12, 192, 192).asImageBitmap(),
        banner = banner.asImageBitmap(),
        accent = accent,
        isTv = true,
    )

    private fun banner(top: Int, bottom: Int, glyph: (Canvas, Paint) -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(384, 216, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 384f, 216f, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 384f, 216f, p)
        p.shader = null
        glyph(c, p)
        return bmp
    }

    /** A television with an antenna and a play mark. */
    private fun drawTv(c: Canvas, p: Paint) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 9f
        p.color = AColor.WHITE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        c.drawRoundRect(RectF(112f, 68f, 272f, 156f), 18f, 18f, p)
        c.drawLine(160f, 176f, 224f, 176f, p)
        c.drawLine(192f, 68f, 158f, 38f, p)
        c.drawLine(192f, 68f, 226f, 38f, p)
        p.style = Paint.Style.FILL
        val tri = Path().apply {
            moveTo(180f, 90f)
            lineTo(180f, 134f)
            lineTo(218f, 112f)
            close()
        }
        c.drawPath(tri, p)
    }

    /** A play mark in a circle. */
    private fun drawPlay(c: Canvas, p: Paint) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 9f
        p.color = AColor.WHITE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        c.drawCircle(192f, 108f, 58f, p)
        p.style = Paint.Style.FILL
        val tri = Path().apply {
            moveTo(176f, 80f)
            lineTo(176f, 136f)
            lineTo(224f, 108f)
            close()
        }
        c.drawPath(tri, p)
    }
}
