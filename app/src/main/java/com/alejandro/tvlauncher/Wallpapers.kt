package com.alejandro.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Wallpapers {

    const val CUSTOM = "custom"

    class Preset(val id: String, @StringRes val nameRes: Int, val full: Int, val thumb: Int)

    val presets = listOf(
        Preset("mask", R.string.wp_mask, R.drawable.wp_mask, R.drawable.wpt_mask),
        Preset("aurora", R.string.wp_aurora, R.drawable.wp_aurora, R.drawable.wpt_aurora),
        Preset("dusk", R.string.wp_dusk, R.drawable.wp_dusk, R.drawable.wpt_dusk),
        Preset("ocean", R.string.wp_ocean, R.drawable.wp_ocean, R.drawable.wpt_ocean),
        Preset("ember", R.string.wp_ember, R.drawable.wp_ember, R.drawable.wpt_ember),
        Preset("graphite", R.string.wp_graphite, R.drawable.wp_graphite, R.drawable.wpt_graphite),
    )

    /** [full] is the wallpaper; [blurred] is a tiny, pre-blurred copy used behind glass surfaces. */
    class Loaded(val full: ImageBitmap, val blurred: ImageBitmap)

    fun customFile(context: Context): File = File(context.filesDir, "wallpaper_custom.jpg")

    /** A new photo each day, downloaded from [DAILY_SOURCE] (a free random-photo service, seeded by the date). */
    const val DAILY = "daily"
    private const val DAILY_SOURCE = "https://picsum.photos/seed/%s/1920/1080"

    fun dailyFile(context: Context): File = File(context.filesDir, "wallpaper_daily.jpg")

    suspend fun load(context: Context, id: String): Loaded = withContext(Dispatchers.Default) {
        val bmp = bitmapFor(context, id)
        Loaded(bmp.asImageBitmap(), blur(bmp).asImageBitmap())
    }

    /** Just the picture, without the glass blur. Used by the screensaver. */
    suspend fun loadFull(context: Context, id: String): ImageBitmap = withContext(Dispatchers.Default) {
        bitmapFor(context, id).asImageBitmap()
    }

    private fun bitmapFor(context: Context, id: String): Bitmap =
        when (id) {
            CUSTOM -> decodeFile(customFile(context))
            DAILY -> decodeFile(dailyFile(context))
            else -> null
        } ?: decodeRes(context, (presets.firstOrNull { it.id == id } ?: presets[0]).full)

    /** Every wallpaper the slideshow / screensaver can cycle through. */
    fun cycleIds(context: Context): List<String> =
        presets.map { it.id } +
            (if (customFile(context).exists()) listOf(CUSTOM) else emptyList()) +
            (if (dailyFile(context).exists()) listOf(DAILY) else emptyList())

    /** Downloads today's photo if we don't have it yet. Returns true when the picture changed. */
    suspend fun refreshDaily(context: Context): Boolean = withContext(Dispatchers.IO) {
        val sp = context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        if (sp.getString("daily_date", "") == today && dailyFile(context).exists()) return@withContext false
        runCatching {
            val conn = URL(DAILY_SOURCE.format(today)).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            val tmp = File(context.filesDir, "wallpaper_daily.tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            // Only replace yesterday's photo if what we got really is an image.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, bounds)
            if (bounds.outWidth <= 0) {
                tmp.delete()
                false
            } else {
                tmp.renameTo(dailyFile(context))
                sp.edit().putString("daily_date", today).apply()
                true
            }
        }.getOrDefault(false)
    }

    private fun decodeRes(context: Context, resId: Int): Bitmap =
        BitmapFactory.decodeResource(context.resources, resId)

    private fun decodeFile(file: File): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 2400) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /** Shrink to a tiny image and box-blur it a few times. Scaled back up it reads as frosted glass. */
    private fun blur(src: Bitmap): Bitmap {
        val w = 192
        val h = 108
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)

        val r = IntArray(w * h) { (px[it] shr 16) and 0xFF }
        val g = IntArray(w * h) { (px[it] shr 8) and 0xFF }
        val b = IntArray(w * h) { px[it] and 0xFF }
        repeat(3) {
            boxBlur(r, w, h, 4)
            boxBlur(g, w, h, 4)
            boxBlur(b, w, h, 4)
        }
        for (i in px.indices) {
            px[i] = (0xFF shl 24) or (r[i].coerceIn(0, 255) shl 16) or (g[i].coerceIn(0, 255) shl 8) or b[i].coerceIn(0, 255)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxBlur(ch: IntArray, w: Int, h: Int, radius: Int) {
        val tmp = IntArray(ch.size)
        val div = 2 * radius + 1

        for (y in 0 until h) {
            var sum = 0
            for (i in -radius..radius) sum += ch[y * w + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = sum / div
                val add = (x + radius + 1).coerceAtMost(w - 1)
                val sub = (x - radius).coerceAtLeast(0)
                sum += ch[y * w + add] - ch[y * w + sub]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (i in -radius..radius) sum += tmp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                ch[y * w + x] = sum / div
                val add = (y + radius + 1).coerceAtMost(h - 1)
                val sub = (y - radius).coerceAtLeast(0)
                sum += tmp[add * w + x] - tmp[sub * w + x]
            }
        }
    }
}
