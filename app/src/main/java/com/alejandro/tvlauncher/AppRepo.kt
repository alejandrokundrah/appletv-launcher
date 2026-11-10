package com.alejandro.tvlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
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
    /** Dominant colour of the icon, used to tint the backdrop. */
    val accent: Color,
)

object AppRepo {

    private const val ICON_SIZE = 256

    suspend fun load(context: Context): List<AppEntry> = withContext(Dispatchers.Default) {
        val pm = context.packageManager

        val leanback = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
        )
        val regular = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )

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

                val bitmap = info.loadIcon(pm).toBitmap(ICON_SIZE)
                AppEntry(
                    label = info.loadLabel(pm).toString(),
                    packageName = pkg,
                    launchIntent = intent,
                    icon = bitmap.asImageBitmap(),
                    accent = dominantColor(bitmap),
                )
            }.getOrNull()
        }
    }

    private fun Drawable.toBitmap(size: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null && bitmap.width >= size) {
            return Bitmap.createScaledBitmap(bitmap, size, size, true)
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, size, size)
        draw(canvas)
        return bmp
    }

    /** Average colour of the icon, pushed towards a rich, fairly dark tone for backdrops. */
    private fun dominantColor(bmp: Bitmap): Color {
        val px = Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(px, hsl)
        hsl[1] = (hsl[1] * 1.25f).coerceIn(0.25f, 0.85f)
        hsl[2] = hsl[2].coerceIn(0.28f, 0.45f)
        return Color(ColorUtils.HSLToColor(hsl))
    }
}
