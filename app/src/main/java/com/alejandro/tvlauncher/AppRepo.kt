package com.alejandro.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val label: String,
    val packageName: String,
    val launchIntent: Intent,
    val icon: ImageBitmap,
    /** Wide 16:9 artwork supplied by Android TV apps (null for phone apps). */
    val banner: ImageBitmap?,
    /** Dominant colour of the artwork, used to tint the backdrop. */
    val accent: Color,
    val isTv: Boolean,
)

object AppRepo {

    private const val ICON_SIZE = 192
    private const val BANNER_W = 384
    private const val BANNER_H = 216

    suspend fun load(context: Context): List<AppEntry> = withContext(Dispatchers.Default) {
        val pm = context.packageManager

        val leanback = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
        )
        val regular = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val tvPackages = leanback.map { it.activityInfo.packageName }.toSet()

        // Leanback apps first (they are the TV-optimised ones), then everything else.
        val seen = HashSet<String>()
        val ordered = (leanback.sortedBy { it.loadLabel(pm).toString().lowercase() } +
            regular.sortedBy { it.loadLabel(pm).toString().lowercase() })
            .filter { it.activityInfo.packageName != context.packageName }
            .filter { seen.add(it.activityInfo.packageName) }

        ordered.mapNotNull { info ->
            runCatching {
                val pkg = info.activityInfo.packageName
                val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
                    ?: pm.getLaunchIntentForPackage(pkg)
                    ?: return@runCatching null
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

                val iconBmp = info.loadIcon(pm).renderFit(ICON_SIZE)
                val bannerBmp: Bitmap? = runCatching {
                    val d = info.activityInfo.loadBanner(pm)
                        ?: info.activityInfo.applicationInfo.loadBanner(pm)
                    // Only use banners that really are wide (roughly 16:9). Anything else would be
                    // stretched or heavily cropped, so those apps get their icon on glass instead.
                    val ratio = if (d != null && d.intrinsicHeight > 0) {
                        d.intrinsicWidth.toFloat() / d.intrinsicHeight
                    } else {
                        0f
                    }
                    if (d != null && ratio in 1.5f..2.1f) d.renderCover(BANNER_W, BANNER_H) else null
                }.getOrNull()

                AppEntry(
                    label = info.loadLabel(pm).toString(),
                    packageName = pkg,
                    launchIntent = intent,
                    icon = iconBmp.asImageBitmap(),
                    banner = bannerBmp?.asImageBitmap(),
                    accent = dominantColor(bannerBmp ?: iconBmp),
                    isTv = pkg in tvPackages,
                )
            }.getOrNull()
        }
    }

    /** Draws the drawable keeping its aspect ratio, filling the whole bitmap (centre-cropped). */
    private fun Drawable.renderCover(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val iw = if (intrinsicWidth > 0) intrinsicWidth else width
        val ih = if (intrinsicHeight > 0) intrinsicHeight else height
        val scale = maxOf(width.toFloat() / iw, height.toFloat() / ih)
        val dw = (iw * scale).toInt()
        val dh = (ih * scale).toInt()
        val left = (width - dw) / 2
        val top = (height - dh) / 2
        setBounds(left, top, left + dw, top + dh)
        draw(Canvas(bmp))
        return bmp
    }

    /** Draws the drawable keeping its aspect ratio, fully visible, centred in a square bitmap. */
    private fun Drawable.renderFit(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val iw = if (intrinsicWidth > 0) intrinsicWidth else size
        val ih = if (intrinsicHeight > 0) intrinsicHeight else size
        val scale = minOf(size.toFloat() / iw, size.toFloat() / ih)
        val dw = (iw * scale).toInt()
        val dh = (ih * scale).toInt()
        val left = (size - dw) / 2
        val top = (size - dh) / 2
        setBounds(left, top, left + dw, top + dh)
        draw(Canvas(bmp))
        return bmp
    }

    /** Average colour of the artwork, pushed towards a rich, fairly dark tone for backdrops. */
    private fun dominantColor(bmp: Bitmap): Color {
        val px = Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(px, hsl)
        hsl[1] = (hsl[1] * 1.25f).coerceIn(0.25f, 0.85f)
        hsl[2] = hsl[2].coerceIn(0.28f, 0.50f)
        return Color(ColorUtils.HSLToColor(hsl))
    }
}
