package tv.aura.ui.status

import tv.aura.home.R
import tv.aura.app.*
import tv.aura.design.*
import tv.aura.system.*
import tv.aura.content.legacy.*
import tv.aura.ui.hero.*
import tv.aura.ui.dock.*
import tv.aura.ui.control.*
import tv.aura.ui.settings.*
import tv.aura.ui.ambient.*

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Tiny Open-Meteo client (free, no API key). Falls back to OpenStreetMap for local city spellings. */
object Weather {
    class Place(val name: String, val lat: Double, val lon: Double)
    class Now(val temp: Int, val code: Int)

    fun geocode(city: String): Place? = runCatching {
        val q = URLEncoder.encode(city, "UTF-8")
        JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=$q"))
            .optJSONArray("results")?.optJSONObject(0)?.let {
                return Place(it.getString("name"), it.getDouble("latitude"), it.getDouble("longitude"))
            }
        JSONArray(get("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=$q")).optJSONObject(0)?.let {
            val name = it.optString("name").ifEmpty { it.getString("display_name").substringBefore(',') }
            return Place(name, it.getString("lat").toDouble(), it.getString("lon").toDouble())
        }
        null
    }.getOrNull()

    fun current(lat: Double, lon: Double): Now? = runCatching {
        val c = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code"))
            .getJSONObject("current")
        Now(Math.round(c.getDouble("temperature_2m")).toInt(), c.getInt("weather_code"))
    }.getOrNull()

    fun describe(code: Int) = when (kind(code)) {
        "sun" -> "Clear"
        "part" -> "Partly Cloudy"
        "cloud" -> "Cloudy"
        "fog" -> "Fog"
        "rain" -> "Rain"
        "snow" -> "Snow"
        else -> "Thunderstorms"
    }

    /** Simple symbol for a WMO weather code. */
    fun kind(code: Int) = when {
        code == 0 -> "sun"
        code <= 2 -> "part"
        code == 3 -> "cloud"
        code <= 48 -> "fog"
        code <= 67 || code in 80..82 -> "rain"
        code <= 86 -> "snow"
        else -> "storm"
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Home-TV-Launcher/1.0")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
}
