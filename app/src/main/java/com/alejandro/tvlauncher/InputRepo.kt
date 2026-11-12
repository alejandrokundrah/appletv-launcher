package com.alejandro.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Typeface
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HDMI / composite / other pass-through inputs of the TV box or TV, shown as tiles so you can
 * switch source without leaving the launcher. Boxes with no TV input framework simply have none.
 */
object InputRepo {

    const val PREFIX = "input:"

    suspend fun load(context: Context): List<AppEntry> = withContext(Dispatchers.Default) {
        runCatching {
            val tvm = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            tvm?.tvInputList.orEmpty()
                .filter { it.isPassthroughInput }
                .map { toEntry(context, it) }
        }.getOrDefault(emptyList<AppEntry>())
    }

    private fun toEntry(context: Context, info: TvInputInfo): AppEntry {
        val label = (info.loadCustomLabel(context) ?: info.loadLabel(context))?.toString()
            ?.takeIf { it.isNotBlank() } ?: typeName(info.type)
        val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return AppEntry(
            label = label,
            packageName = PREFIX + info.id,
            launchIntent = intent,
            icon = glyph(shortName(info.type, label)).asImageBitmap(),
            banner = null,
            accent = Color(0xFF3B5BA9),
            isTv = true,
            isInput = true,
        )
    }

    private fun typeName(type: Int): String = when (type) {
        TvInputInfo.TYPE_HDMI -> "HDMI"
        TvInputInfo.TYPE_COMPOSITE -> "AV"
        TvInputInfo.TYPE_COMPONENT -> "Component"
        TvInputInfo.TYPE_SVIDEO -> "S-Video"
        TvInputInfo.TYPE_SCART -> "SCART"
        TvInputInfo.TYPE_VGA -> "VGA"
        TvInputInfo.TYPE_DVI -> "DVI"
        TvInputInfo.TYPE_DISPLAY_PORT -> "DisplayPort"
        else -> "Input"
    }

    /** Text drawn on the tile: "HDMI 2" style labels keep their number, anything else gets the type. */
    private fun shortName(type: Int, label: String): String {
        val digits = label.filter { it.isDigit() }.take(2)
        return if (digits.isNotEmpty()) typeName(type) + " " + digits else typeName(type)
    }

    /** A simple round-rect "port" glyph with the input name, drawn once. No image resources needed. */
    private fun glyph(text: String): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.argb(255, 255, 255, 255) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AColor.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        // Port outline
        c.drawRoundRect(24f, 52f, 168f, 120f, 14f, 14f, stroke)
        // Little notch at the bottom corners, like an HDMI plug
        c.drawRect(44f, 120f, 148f, 130f, body)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AColor.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = if (text.length > 7) 24f else 30f
        }
        c.drawText(text, size / 2f, 94f, label)
        return bmp
    }
}
