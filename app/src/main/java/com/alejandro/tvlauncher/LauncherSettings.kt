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

    var showWeather by boolPref("show_weather", true)

    /** Index into [WeatherRepo.cities]; 0 = detect by IP address. */
    var weatherCity by intPref("weather_city", 0)

    var fahrenheit by boolPref("fahrenheit", false)

    var showNetwork by boolPref("show_network", true)

    /** We only ask for the location permission (needed to read the Wi-Fi name) once automatically. */
    var askedLocation by boolPref("asked_location", false)

    var hidden: Set<String> by mutableStateOf(sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet())
        private set

    fun toggleHidden(packageName: String) {
        hidden = if (packageName in hidden) hidden - packageName else hidden + packageName
        sp.edit().putStringSet("hidden", hidden.toSet()).apply()
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
        hidden = emptySet()
        sp.edit().putStringSet("hidden", emptySet<String>()).apply()
    }
}
