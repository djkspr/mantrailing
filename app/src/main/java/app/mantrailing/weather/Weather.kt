package app.mantrailing.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Météo au moment d'une pose de piste ou d'une recherche : le vent et l'humidité comptent beaucoup pour l'odeur.
 * [time] : heure de la mesure (secondes Unix) ; vent en km/h, direction d'où il vient en degrés (0 = nord).
 */
@Serializable
data class Weather(
    val time: Long,
    val temperatureC: Double,
    val windKmh: Double,
    val windDirection: Int,
    val windGustKmh: Double? = null,
    val humidity: Int? = null,
    val precipitationMm: Double? = null,
    val cloudCover: Int? = null,
) {
    /** « 12 °C · vent de NO 15 km/h (rafales 30) · humidité 70 % · pluie 0,4 mm » */
    val summary: String
        get() = buildList {
            add("%.0f °C".format(Locale.FRENCH, temperatureC))
            add(
                "vent ${windFrom(windDirection)} %.0f km/h".format(Locale.FRENCH, windKmh) +
                    (windGustKmh?.takeIf { it >= windKmh + 10 }?.let { " (rafales %.0f)".format(Locale.FRENCH, it) } ?: ""),
            )
            humidity?.let { add("humidité $it %") }
            add(precipitationMm?.takeIf { it >= 0.1 }?.let { "pluie %.1f mm".format(Locale.FRENCH, it) } ?: sky(cloudCover))
        }.joinToString(" · ")

    /** Version courte pour les listes : « 12 °C, vent de NO 15 km/h ». */
    val short: String get() = "%.0f °C, vent ${windFrom(windDirection)} %.0f km/h".format(Locale.FRENCH, temperatureC, windKmh)
}

/** Direction d'où vient le vent, comme on le dit : « du nord », « d'ouest », « de nord-ouest »… */
private val WIND_FROM = listOf("du nord", "de nord-est", "d'est", "de sud-est", "du sud", "de sud-ouest", "d'ouest", "de nord-ouest")

fun windFrom(degrees: Int): String = WIND_FROM[((((degrees % 360) + 360) % 360) + 22) / 45 % 8]

private fun sky(cloudCover: Int?): String = when {
    cloudCover == null -> "sec"
    cloudCover < 20 -> "ciel dégagé"
    cloudCover < 70 -> "nuageux"
    else -> "couvert"
}

/** Position envoyée au service météo arrondie à 0,01° (environ 1 km) : assez pour la météo, sans plus de précision. */
fun roundForWeather(value: Double): Double = (value * 100).roundToInt() / 100.0

@Serializable
private data class Hourly(
    val time: List<Long> = emptyList(),
    val temperature_2m: List<Double?> = emptyList(),
    val relative_humidity_2m: List<Int?> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
    val cloud_cover: List<Int?> = emptyList(),
    val wind_speed_10m: List<Double?> = emptyList(),
    val wind_direction_10m: List<Int?> = emptyList(),
    val wind_gusts_10m: List<Double?> = emptyList(),
)

@Serializable
private data class Forecast(val hourly: Hourly? = null)

/** Open-Meteo (gratuit, sans clé, Europe) : météo heure par heure, des 3 derniers mois aux prochains jours. */
class WeatherApi(
    private val baseUrl: HttpUrl = "https://api.open-meteo.com/v1/forecast".toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Météo de l'heure la plus proche de [epochSeconds] au point donné ; null si indisponible. */
    suspend fun at(lat: Double, lon: Double, epochSeconds: Long): Weather? = withContext(Dispatchers.IO) {
        val day = Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate()
        val url = baseUrl.newBuilder()
            .addQueryParameter("latitude", roundForWeather(lat).toString())
            .addQueryParameter("longitude", roundForWeather(lon).toString())
            .addQueryParameter(
                "hourly",
                "temperature_2m,relative_humidity_2m,precipitation,cloud_cover,wind_speed_10m,wind_direction_10m,wind_gusts_10m",
            )
            .addQueryParameter("wind_speed_unit", "kmh")
            .addQueryParameter("timeformat", "unixtime")
            .addQueryParameter("timezone", "UTC")
            .addQueryParameter("start_date", day.toString())
            .addQueryParameter("end_date", day.toString())
            .build()
        val body = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.string() else null }
        }.getOrNull() ?: return@withContext null
        val hourly = runCatching { json.decodeFromString(Forecast.serializer(), body).hourly }.getOrNull() ?: return@withContext null
        val i = hourly.time.indices.minByOrNull { abs(hourly.time[it] - epochSeconds) } ?: return@withContext null
        if (abs(hourly.time[i] - epochSeconds) > 3_600) return@withContext null
        Weather(
            time = hourly.time[i],
            temperatureC = hourly.temperature_2m.getOrNull(i) ?: return@withContext null,
            windKmh = hourly.wind_speed_10m.getOrNull(i) ?: return@withContext null,
            windDirection = hourly.wind_direction_10m.getOrNull(i) ?: return@withContext null,
            windGustKmh = hourly.wind_gusts_10m.getOrNull(i),
            humidity = hourly.relative_humidity_2m.getOrNull(i),
            precipitationMm = hourly.precipitation.getOrNull(i),
            cloudCover = hourly.cloud_cover.getOrNull(i),
        )
    }
}
