package com.alejandro.tvlauncher

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import kotlin.math.roundToInt

// ---------------------------------------------------------------- weather

enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Storm }

data class WeatherInfo(
    val temp: Int,
    val high: Int,
    val low: Int,
    val kind: WeatherKind,
    val label: String,
    val night: Boolean,
    val place: String,
    val unit: String,
)

/** Current weather from Open-Meteo (free, no API key). */
object WeatherRepo {

    class City(val name: String, val lat: Double, val lon: Double)

    /** Index 0 means "detect from the box's public IP address". */
    val cities = listOf(
        City("Auto (from IP address)", 0.0, 0.0),
        City("Dakar", 14.6937, -17.4441),
        City("Bambey", 14.7000, -16.4500),
        City("Malabo", 3.7500, 8.7833),
        City("Bata", 1.8639, 9.7658),
        City("Lagos", 6.5244, 3.3792),
        City("Abidjan", 5.3600, -4.0083),
        City("Accra", 5.6037, -0.1870),
        City("Casablanca", 33.5731, -7.5898),
        City("Cairo", 30.0444, 31.2357),
        City("Nairobi", -1.2921, 36.8219),
        City("Johannesburg", -26.2041, 28.0473),
        City("Paris", 48.8566, 2.3522),
        City("London", 51.5074, -0.1278),
        City("Madrid", 40.4168, -3.7038),
        City("Istanbul", 41.0082, 28.9784),
        City("Dubai", 25.2048, 55.2708),
        City("New York", 40.7128, -74.0060),
    )

    suspend fun fetch(cityIndex: Int, fahrenheit: Boolean): WeatherInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val idx = cityIndex.coerceIn(0, cities.size - 1)
            val place: String
            val lat: Double
            val lon: Double
            if (idx == 0) {
                val j = JSONObject(httpGet("https://ipwho.is/"))
                if (!j.optBoolean("success", false)) return@runCatching null
                lat = j.getDouble("latitude")
                lon = j.getDouble("longitude")
                place = j.optString("city", "")
            } else {
                val c = cities[idx]
                lat = c.lat
                lon = c.lon
                place = c.name
            }

            val unitParam = if (fahrenheit) "fahrenheit" else "celsius"
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1" +
                "&timezone=auto&temperature_unit=$unitParam"
            val root = JSONObject(httpGet(url))
            val cur = root.getJSONObject("current")
            val daily = root.getJSONObject("daily")
            val (kind, label) = describe(cur.getInt("weather_code"))

            WeatherInfo(
                temp = cur.getDouble("temperature_2m").roundToInt(),
                high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
                kind = kind,
                label = label,
                night = cur.getInt("is_day") == 0,
                place = place,
                unit = if (fahrenheit) "°F" else "°C",
            )
        }.getOrNull()
    }

    private fun describe(code: Int): Pair<WeatherKind, String> = when (code) {
        0 -> WeatherKind.Clear to "Clear"
        1 -> WeatherKind.Clear to "Mostly clear"
        2 -> WeatherKind.PartlyCloudy to "Partly cloudy"
        3 -> WeatherKind.Cloudy to "Overcast"
        45, 48 -> WeatherKind.Fog to "Fog"
        51, 53, 55, 56, 57 -> WeatherKind.Rain to "Drizzle"
        61, 63, 65, 66, 67 -> WeatherKind.Rain to "Rain"
        71, 73, 75, 77 -> WeatherKind.Snow to "Snow"
        80, 81, 82 -> WeatherKind.Rain to "Showers"
        85, 86 -> WeatherKind.Snow to "Snow showers"
        95, 96, 99 -> WeatherKind.Storm to "Thunderstorm"
        else -> WeatherKind.Cloudy to "Cloudy"
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
    /** Wi-Fi name, "Ethernet", "Offline"... */
    val name: String,
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
        if (active == null || !active.isConnected) return NetInfo(NetKind.Offline, "Offline", 0, null)

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
                NetInfo(NetKind.Wifi, ssid ?: "Wi-Fi", level.coerceIn(0, 4), ip)
            }
            ConnectivityManager.TYPE_ETHERNET -> NetInfo(NetKind.Ethernet, "Ethernet", 4, ip)
            else -> NetInfo(NetKind.Other, active.typeName ?: "Connected", 4, ip)
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
