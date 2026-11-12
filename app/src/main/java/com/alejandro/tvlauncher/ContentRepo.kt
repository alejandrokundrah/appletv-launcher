package com.alejandro.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** One title an app has put on the Android TV home screen ("Continue watching", recommendations, ...). */
data class ContentItem(
    val packageName: String,
    val title: String,
    val posterUri: String?,
    /** `intent:` URI that opens this title inside its app. */
    val intentUri: String?,
    /** True for "Continue watching" entries (what the person actually watched). */
    val watched: Boolean,
    val lastEngagement: Long,
)

/**
 * Reads titles that apps publish to Android's TV provider (the data behind Android TV's own
 * "Continue watching" and recommendation rows). Needs the READ_TV_LISTINGS permission, and only
 * returns what the app itself chose to publish: a launcher can't see an app's private watch history.
 */
object ContentRepo {

    private const val PKG = "package_name"
    private const val TITLE = "title"
    private const val POSTER = "poster_art_uri"
    private const val THUMB = "thumbnail_uri"
    private const val INTENT = "intent_uri"
    private const val ENGAGED = "last_engagement_time_utc_millis"

    private const val MAX_ITEMS = 20

    suspend fun load(context: Context, packageName: String): List<ContentItem> =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < 26) return@withContext emptyList()
            val watchNext = query(context, "content://android.media.tv/watch_next_program", packageName, true)
            val previews = query(context, "content://android.media.tv/preview_program", packageName, false)
            val seen = HashSet<String>()
            (watchNext.sortedByDescending { it.lastEngagement } + previews)
                .filter { seen.add(it.title.lowercase()) }
                .take(MAX_ITEMS)
        }

    private fun query(context: Context, uri: String, packageName: String, watched: Boolean): List<ContentItem> {
        val out = ArrayList<ContentItem>()
        // The provider may refuse (no permission) or the table may be missing on this box: that's just "nothing to show".
        runCatching {
            val projection = if (watched) {
                arrayOf(PKG, TITLE, POSTER, THUMB, INTENT, ENGAGED)
            } else {
                arrayOf(PKG, TITLE, POSTER, THUMB, INTENT)
            }
            context.contentResolver.query(Uri.parse(uri), projection, null, null, null)?.use { c ->
                val iPkg = c.getColumnIndex(PKG)
                val iTitle = c.getColumnIndex(TITLE)
                val iPoster = c.getColumnIndex(POSTER)
                val iThumb = c.getColumnIndex(THUMB)
                val iIntent = c.getColumnIndex(INTENT)
                val iEngaged = c.getColumnIndex(ENGAGED)
                while (c.moveToNext() && out.size < 200) {
                    if (iPkg < 0 || c.getString(iPkg) != packageName) continue
                    val title = if (iTitle >= 0) c.getString(iTitle) else null
                    if (title.isNullOrBlank()) continue
                    out += ContentItem(
                        packageName = packageName,
                        title = title,
                        posterUri = (if (iPoster >= 0) c.getString(iPoster) else null)
                            ?: (if (iThumb >= 0) c.getString(iThumb) else null),
                        intentUri = if (iIntent >= 0) c.getString(iIntent) else null,
                        watched = watched,
                        lastEngagement = if (iEngaged >= 0) c.getLong(iEngaged) else 0L,
                    )
                }
            }
        }
        return out
    }
}

/** Small poster loader (content:// or http(s)) with an in-memory cache. No image library needed. */
object PosterLoader {

    private const val TARGET_W = 480

    private val cache = object : LruCache<String, ImageBitmap>(40) {}

    suspend fun load(context: Context, uri: String): ImageBitmap? {
        cache.get(uri)?.let { return it }
        val bmp = withContext(Dispatchers.IO) { runCatching { fetch(context, uri) }.getOrNull() } ?: return null
        val img = bmp.asImageBitmap()
        cache.put(uri, img)
        return img
    }

    private fun fetch(context: Context, uri: String): Bitmap? {
        // Two passes: read the size, then decode downsampled so big posters don't eat memory.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_W) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open(context, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun open(context: Context, uri: String): java.io.InputStream? {
        val u = Uri.parse(uri)
        return when (u.scheme?.lowercase()) {
            "http", "https" -> {
                val conn = (URL(uri).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 8000
                }
                conn.inputStream
            }
            else -> context.contentResolver.openInputStream(u)
        }
    }
}
