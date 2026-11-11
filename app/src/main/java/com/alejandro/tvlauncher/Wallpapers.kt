package com.alejandro.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Wallpapers {

    const val CUSTOM = "custom"

    class Preset(val id: String, val name: String, val full: Int, val thumb: Int)

    val presets = listOf(
        Preset("mask", "Mask", R.drawable.wp_mask, R.drawable.wpt_mask),
        Preset("aurora", "Aurora", R.drawable.wp_aurora, R.drawable.wpt_aurora),
        Preset("dusk", "Dusk", R.drawable.wp_dusk, R.drawable.wpt_dusk),
        Preset("ocean", "Ocean", R.drawable.wp_ocean, R.drawable.wpt_ocean),
        Preset("ember", "Ember", R.drawable.wp_ember, R.drawable.wpt_ember),
        Preset("graphite", "Graphite", R.drawable.wp_graphite, R.drawable.wpt_graphite),
    )

    /** [full] is the wallpaper; [blurred] is a tiny, pre-blurred copy used behind glass surfaces. */
    class Loaded(val full: ImageBitmap, val blurred: ImageBitmap)

    fun customFile(context: Context): File = File(context.filesDir, "wallpaper_custom.jpg")

    suspend fun load(context: Context, id: String): Loaded = withContext(Dispatchers.Default) {
        val bmp = (if (id == CUSTOM) decodeFile(customFile(context)) else null)
            ?: decodeRes(context, (presets.firstOrNull { it.id == id } ?: presets[0]).full)
        Loaded(bmp.asImageBitmap(), blur(bmp).asImageBitmap())
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
