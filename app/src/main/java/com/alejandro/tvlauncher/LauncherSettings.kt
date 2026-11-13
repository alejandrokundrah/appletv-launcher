package com.alejandro.tvlauncher

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * All user-facing launcher options. Every property is Compose state that also
 * writes itself to SharedPreferences, so the UI updates live and survives restarts.
 */
class LauncherSettings(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    private fun intPref(key: String, def: Int): ReadWriteProperty<Any?, Int> =
        object : ReadWriteProperty<Any?, Int> {
            private val state = mutableIntStateOf(sp.getInt(key, def))
            override fun getValue(thisRef: Any?, property: KProperty<*>): Int = state.intValue
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
                state.intValue = value
                sp.edit().putInt(key, value).apply()
            }
        }

    private fun floatPref(key: String, def: Float): ReadWriteProperty<Any?, Float> =
        object : ReadWriteProperty<Any?, Float> {
            private val state = mutableFloatStateOf(sp.getFloat(key, def))
            override fun getValue(thisRef: Any?, property: KProperty<*>): Float = state.floatValue
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
                state.floatValue = value
                sp.edit().putFloat(key, value).apply()
            }
        }

    private fun boolPref(key: String, def: Boolean): ReadWriteProperty<Any?, Boolean> =
        object : ReadWriteProperty<Any?, Boolean> {
            private val state = mutableStateOf(sp.getBoolean(key, def))
            override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = state.value
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
                state.value = value
                sp.edit().putBoolean(key, value).apply()
            }
        }

    private fun stringPref(key: String, def: String): ReadWriteProperty<Any?, String> =
        object : ReadWriteProperty<Any?, String> {
            private val state = mutableStateOf(sp.getString(key, def) ?: def)
            override fun getValue(thisRef: Any?, property: KProperty<*>): String = state.value
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
                state.value = value
                sp.edit().putString(key, value).apply()
            }
        }

    /** Preset id ("aurora", "dusk", ...) or [Wallpapers.CUSTOM]. */
    var wallpaper by stringPref("wallpaper", "mask")

    /** Bumped whenever the custom photo file is replaced, so the loader re-runs. */
    var wallpaperRev by intPref("wallpaper_rev", 0)

    /** 0f..0.7f black overlay on top of the wallpaper. */
    var dim by floatPref("dim", 0.10f)

    /** Index into [GlassStyle.entries]. */
    var glass by intPref("glass", 1)

    /** 0 = small, 1 = medium, 2 = large tiles. */
    var tileSize by intPref("tile_size", 1)

    /** 0 = app name only on the focused tile, 1 = always. */
    var labels by intPref("labels", 0)

    /** Tint the backdrop with the focused app's colour. */
    var accentTint by boolPref("accent_tint", false)

    /** Show apps that are not Android TV apps (phone apps with a normal launcher icon). */
    var showNonTv by boolPref("show_non_tv", true)

    /** 0 = follow system, 1 = 12-hour, 2 = 24-hour. */
    var clock by intPref("clock", 0)

    /** "" = follow the system language, otherwise a language code such as "en" or "fr". */
    var language by stringPref("language", "")

    var showWeather by boolPref("show_weather", true)

    /** Index into [WeatherRepo.cities]; 0 = detect by IP address. */
    var weatherCity by intPref("weather_city", 0)

    var fahrenheit by boolPref("fahrenheit", false)

    var showNetwork by boolPref("show_network", true)

    /** 0 = auto (lighter look on weak GPUs), 1 = always lighter, 2 = always full glass. */
    var performance by intPref("performance", 0)

    /** Show "continue watching" / recent titles from the focused app (e.g. Netflix) above the tiles. */
    var showContent by boolPref("show_content", true)

    /** We only ask for the TV-listings permission (needed to read other apps' titles) once automatically. */
    var askedTvListings by boolPref("asked_tv_listings", false)

    /** We only ask for the location permission (needed to read the Wi-Fi name) once automatically. */
    var askedLocation by boolPref("asked_location", false)

    /** 0 = custom order (A-Z until you move something), 1 = recently used first. Pinned apps always lead. */
    var sortMode by intPref("sort_mode", 0)

    /** Show HDMI / composite inputs as tiles at the end of the row. */
    var showInputs by boolPref("show_inputs", true)

    /** Minutes between wallpaper changes; 0 = off. Cycles through the built-in and custom wallpapers. */
    var slideshow by intPref("slideshow", 0)

    /** Tint the focus ring and glow with the focused app's colour. */
    var accentFocus by boolPref("accent_focus", true)

    /** Play the system's navigation / click sounds while moving around (follows the system sound-effects setting). */
    var sounds by boolPref("sounds", false)

    /** Optional movie/TV WebView fallbacks, tried after the primary Vidly player fails. */
    var zxcstreamPlayer by boolPref("zxcstream_player", false)
    var vidsrcPlayer by boolPref("vidsrc_player", false)
    var customPlayerEnabled by boolPref("custom_player_enabled", false)
    var customPlayerUrl by stringPref("custom_player_url", "")

    var hidden: Set<String> by mutableStateOf(sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet())
        private set

    fun toggleHidden(packageName: String) {
        hidden = if (packageName in hidden) hidden - packageName else hidden + packageName
        sp.edit().putStringSet("hidden", hidden.toSet()).apply()
    }

    // ---- app order, pins and launch history -------------------------------------------------

    private fun readList(key: String): List<String> =
        (sp.getString(key, "") ?: "").split(',').filter { it.isNotBlank() }

    /** Pinned apps, in the order they were pinned / moved. They always come first. */
    var pinned: List<String> by mutableStateOf(readList("pinned"))
        private set

    /** The custom order of the unpinned apps. Apps not listed here follow alphabetically. */
    var order: List<String> by mutableStateOf(readList("order"))
        private set

    /** When each app was last opened from here (millis), for "recently used first". */
    var launched: Map<String, Long> by mutableStateOf(
        (sp.getString("launched", "") ?: "").split(';').mapNotNull {
            val p = it.split('=')
            if (p.size == 2) p[1].toLongOrNull()?.let { t -> p[0] to t } else null
        }.toMap()
    )
        private set

    fun recordLaunch(packageName: String) {
        // Keep only the 40 most recent so the preference stays small.
        launched = (launched + (packageName to System.currentTimeMillis()))
            .entries.sortedByDescending { it.value }.take(40).associate { it.key to it.value }
        sp.edit().putString("launched", launched.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }

    /** Stream URLs of the Live TV channels marked as favourites, in the order they were added. */
    var favoriteChannels: List<String> by mutableStateOf(
        (sp.getString("fav_channels", "") ?: "").split('\n').filter { it.isNotBlank() }
    )
        private set

    fun toggleFavoriteChannel(url: String) {
        favoriteChannels = if (url in favoriteChannels) favoriteChannels - url else favoriteChannels + url
        sp.edit().putString("fav_channels", favoriteChannels.joinToString("\n")).apply()
    }

    fun togglePinned(packageName: String) {
        pinned = if (packageName in pinned) pinned - packageName else pinned + packageName
        sp.edit().putString("pinned", pinned.joinToString(",")).apply()
    }

    /**
     * Moves [packageName] one place left (-1) or right (+1) inside [arranged], the list exactly as
     * shown on screen. Pinned and unpinned apps stay in their own group.
     */
    fun move(packageName: String, delta: Int, arranged: List<String>) {
        val from = arranged.indexOf(packageName)
        val to = from + delta
        if (from < 0 || to !in arranged.indices) return
        if ((arranged[to] in pinned) != (packageName in pinned)) return
        val list = arranged.toMutableList()
        java.util.Collections.swap(list, from, to)
        if (sortMode == 1) sortMode = 0
        val pinnedSet = pinned.toSet()
        pinned = list.filter { it in pinnedSet }
        val others = list.filter { it !in pinnedSet }
        order = others + order.filter { it !in others && it !in pinnedSet }
        sp.edit().putString("pinned", pinned.joinToString(",")).putString("order", order.joinToString(",")).apply()
    }

    /** The apps in the order they should appear: pinned first, then by custom order or recent use. */
    fun arrange(apps: List<AppEntry>): List<AppEntry> {
        val position = order.withIndex().associate { it.value to it.index }
        val base = if (sortMode == 1) {
            apps.sortedWith(
                compareByDescending<AppEntry> { launched[it.packageName] ?: 0L }
                    .thenBy { apps.indexOf(it) }
            )
        } else {
            apps.sortedBy { position[it.packageName] ?: (order.size + apps.indexOf(it)) }
        }
        val pinOrder = pinned.withIndex().associate { it.value to it.index }
        val (pins, rest) = base.partition { it.packageName in pinOrder }
        return pins.sortedBy { pinOrder[it.packageName] } + rest
    }

    fun reset() {
        wallpaper = "mask"
        dim = 0.10f
        glass = 1
        tileSize = 1
        labels = 0
        accentTint = false
        showNonTv = true
        clock = 0
        showWeather = true
        weatherCity = 0
        fahrenheit = false
        showNetwork = true
        performance = 0
        showContent = true
        sortMode = 0
        showInputs = true
        slideshow = 0
        accentFocus = true
        sounds = false
        zxcstreamPlayer = false
        vidsrcPlayer = false
        customPlayerEnabled = false
        customPlayerUrl = ""
        pinned = emptyList()
        order = emptyList()
        hidden = emptySet()
        sp.edit()
            .putStringSet("hidden", emptySet<String>())
            .putString("pinned", "")
            .putString("order", "")
            .apply()
    }
}
