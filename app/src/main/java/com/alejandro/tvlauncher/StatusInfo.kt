package com.alejandro.tvlauncher

import android.content.Context
import androidx.annotation.StringRes
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------- weather

enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Storm }

data class WeatherInfo(
    val temp: Int,
    val high: Int,
    val low: Int,
    val kind: WeatherKind,
    @StringRes val labelRes: Int,
    val night: Boolean,
    /** City name from the IP lookup (only when [cityIndex] is 0). */
    val place: String,
    /** Index into [WeatherRepo.cities]; its name is localised by the UI. */
    val cityIndex: Int,
    val unit: String,
)

/** Current weather from Open-Meteo (free, no API key). */
object WeatherRepo {

    class City(@StringRes val nameRes: Int, val lat: Double, val lon: Double)

    /** Index 0 means "detect from the box's public IP address". */
    val cities = listOf(
        City(R.string.city_auto, 0.0, 0.0),
        City(R.string.city_dakar, 14.6937, -17.4441),
        City(R.string.city_bambey, 14.7000, -16.4500),
        City(R.string.city_malabo, 3.7500, 8.7833),
        City(R.string.city_bata, 1.8639, 9.7658),
        City(R.string.city_lagos, 6.5244, 3.3792),
        City(R.string.city_abidjan, 5.3600, -4.0083),
        City(R.string.city_accra, 5.6037, -0.1870),
        City(R.string.city_casablanca, 33.5731, -7.5898),
        City(R.string.city_cairo, 30.0444, 31.2357),
        City(R.string.city_nairobi, -1.2921, 36.8219),
        City(R.string.city_johannesburg, -26.2041, 28.0473),
        City(R.string.city_paris, 48.8566, 2.3522),
        City(R.string.city_london, 51.5074, -0.1278),
        City(R.string.city_madrid, 40.4168, -3.7038),
        City(R.string.city_istanbul, 41.0082, 28.9784),
        City(R.string.city_dubai, 25.2048, 55.2708),
        City(R.string.city_new_york, 40.7128, -74.0060),
    )

    suspend fun fetch(cityIndex: Int, fahrenheit: Boolean): WeatherInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val idx = cityIndex.coerceIn(0, cities.size - 1)
            val place: String
            val lat: Double
            val lon: Double
            if (idx == 0) {
                // ipwho.is can answer with the city name in French
                val ipUrl = if (Locale.getDefault().language == "fr") "https://ipwho.is/?lang=fr" else "https://ipwho.is/"
                val j = JSONObject(httpGet(ipUrl))
                if (!j.optBoolean("success", false)) return@runCatching null
                lat = j.getDouble("latitude")
                lon = j.getDouble("longitude")
                place = j.optString("city", "")
            } else {
                val c = cities[idx]
                lat = c.lat
                lon = c.lon
                place = ""
            }

            val unitParam = if (fahrenheit) "fahrenheit" else "celsius"
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1" +
                "&timezone=auto&temperature_unit=$unitParam"
            val root = JSONObject(httpGet(url))
            val cur = root.getJSONObject("current")
            val daily = root.getJSONObject("daily")
            val (kind, labelRes) = describe(cur.getInt("weather_code"))

            WeatherInfo(
                temp = cur.getDouble("temperature_2m").roundToInt(),
                high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
                kind = kind,
                labelRes = labelRes,
                night = cur.getInt("is_day") == 0,
                place = place,
                cityIndex = idx,
                unit = if (fahrenheit) "°F" else "°C",
            )
        }.getOrNull()
    }

    private fun describe(code: Int): Pair<WeatherKind, Int> = when (code) {
        0 -> WeatherKind.Clear to R.string.wx_clear
        1 -> WeatherKind.Clear to R.string.wx_mostly_clear
        2 -> WeatherKind.PartlyCloudy to R.string.wx_partly_cloudy
        3 -> WeatherKind.Cloudy to R.string.wx_overcast
        45, 48 -> WeatherKind.Fog to R.string.wx_fog
        51, 53, 55, 56, 57 -> WeatherKind.Rain to R.string.wx_drizzle
        61, 63, 65, 66, 67 -> WeatherKind.Rain to R.string.wx_rain
        71, 73, 75, 77 -> WeatherKind.Snow to R.string.wx_snow
        80, 81, 82 -> WeatherKind.Rain to R.string.wx_showers
        85, 86 -> WeatherKind.Snow to R.string.wx_snow_showers
        95, 96, 99 -> WeatherKind.Storm to R.string.wx_storm
        else -> WeatherKind.Cloudy to R.string.wx_cloudy
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "appletv-launcher")
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

// ---------------------------------------------------------------- network

enum class NetKind { Wifi, Ethernet, Other, Offline }

data class NetInfo(
    val kind: NetKind,
    /** Wi-Fi name, or the connection type name for other networks (null = let the UI use a localised word). */
    val name: String?,
    /** Signal strength 0..4 (Wi-Fi only). */
    val level: Int,
    val ip: String?,
)

object NetStatus {

    @Suppress("DEPRECATION")
    fun read(context: Context): NetInfo {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetworkInfo
        if (active == null || !active.isConnected) return NetInfo(NetKind.Offline, null, 0, null)

        val ip = localIp()
        return when (active.type) {
            ConnectivityManager.TYPE_WIFI -> {
                val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo
                // Without the location permission Android hides the name ("<unknown ssid>").
                val ssid = info?.ssid
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != "0x" }
                val level = if (info != null) WifiManager.calculateSignalLevel(info.rssi, 5) else 0
                NetInfo(NetKind.Wifi, ssid, level.coerceIn(0, 4), ip)
            }
            ConnectivityManager.TYPE_ETHERNET -> NetInfo(NetKind.Ethernet, null, 4, ip)
            else -> NetInfo(NetKind.Other, active.typeName, 4, ip)
        }
    }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}
