package app.mantrailing.weenect

import app.mantrailing.tractive.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Session Weenect : le jeton remplace le mot de passe, qui n'est jamais conservé. */
data class WeenectSession(val token: String)

/** Un traceur du compte (collier du chien). */
data class WeenectTracker(val id: String, val name: String)

open class WeenectException(message: String) : IOException(message)

/** Identifiants refusés ou session expirée : il faut se reconnecter. */
class WeenectAuthException(message: String) : WeenectException(message)

/** Au-delà, une position estimée par les antennes ne dit rien du parcours du chien. */
private const val MAX_CELL_RADIUS_M = 50.0

/** Capteurs notés sur les positions Weenect : satellites (GPS) ou estimation par les antennes (imprécise). */
const val WEENECT_GPS_SENSOR = "GPS"
const val WEENECT_CELL_SENSOR = "WEENECT_CELL"

/**
 * Client de l'API de l'appli Weenect. Elle n'est pas publique ni documentée (références : les bibliothèques
 * aioweenect et weenect-api) et peut changer sans prévenir.
 */
class WeenectApi(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun login(email: String, password: String): WeenectSession {
        val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password))
        val response = call("POST", "user/login", null, body) as? JsonObject
        val token = response?.get("access_token")?.jsonPrimitive?.content ?: throw WeenectException("Réponse de connexion Weenect inattendue.")
        return WeenectSession(token)
    }

    suspend fun trackers(session: WeenectSession): List<WeenectTracker> {
        val items = (call("GET", "mytracker", session) as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { runCatching { json.decodeFromJsonElement<RawTracker>(it) }.getOrNull() }
            .map { WeenectTracker(it.id.jsonPrimitive.content, it.name?.takeIf { n -> n.isNotBlank() } ?: "Traceur ${it.id.jsonPrimitive.content}") }
    }

    /** Positions entre [from] et [to], dans l'ordre ; les estimations par antenne trop imprécises sont écartées. */
    suspend fun positions(session: WeenectSession, trackerId: String, from: Instant, to: Instant): List<Position> {
        val path = "mytracker/$trackerId/position?start=${iso(from)}&end=${iso(to)}"
        return parsePositions(call("GET", path, session))
            .filter { it.time in from.epochSecond..to.epochSecond }
    }

    /**
     * Mode « super live » : une position toutes les quelques secondes pendant quelques minutes.
     * À redemander régulièrement pendant une recherche.
     */
    suspend fun superLive(session: WeenectSession, trackerId: String) {
        call("POST", "mytracker/$trackerId/st-mode", session, "{}")
    }

    private suspend fun call(method: String, path: String, session: WeenectSession?, body: String? = null): JsonElement =
        withContext(Dispatchers.IO) {
            val url = baseUrl.resolve(path) ?: throw WeenectException("URL invalide : $path")
            val request = Request.Builder().url(url)
                .header("accept", "application/json, text/plain, */*")
                .header("x-app-version", "0.1.0")
                .apply { if (session != null) header("authorization", "JWT ${session.token}") }
                .method(method, body?.toRequestBody(JSON_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    response.code == 401 || response.code == 403 ->
                        throw WeenectAuthException("Accès refusé par Weenect (HTTP ${response.code})")
                    !response.isSuccessful ->
                        throw WeenectException("Weenect a répondu HTTP ${response.code} : ${text.take(200)}")
                    text.isBlank() -> JsonObject(emptyMap())
                    else -> json.parseToJsonElement(text)
                }
            }
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://apiv4.weenect.com/v4/"
        private val JSON_TYPE = "application/json;charset=UTF-8".toMediaType()
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** « 2026-11-17T09:00:00Z » : sans « + », qui deviendrait une espace dans l'adresse. */
        internal fun iso(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))

        /** Liste de positions (ou objet qui la contient sous « items » / « positions »), triée par heure. */
        internal fun parsePositions(element: JsonElement): List<Position> {
            val array = when (element) {
                is JsonArray -> element
                is JsonObject -> (element["items"] ?: element["positions"] ?: element["position"]) as? JsonArray
                else -> null
            } ?: return emptyList()
            return array.mapNotNull { runCatching { json.decodeFromJsonElement<RawPosition>(it) }.getOrNull()?.toPosition() }
                .distinctBy { it.time }
                .sortedBy { it.time }
        }
    }
}

@Serializable
private data class LoginRequest(val username: String, val password: String)

@Serializable
private data class RawTracker(val id: JsonElement, val name: String? = null)

@Serializable
internal data class RawPosition(
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("date_tracker") val dateTracker: String? = null,
    @SerialName("date_server") val dateServer: String? = null,
    val radius: Double? = null,
    val satellites: Int? = null,
    @SerialName("valid_signal") val validSignal: Boolean? = null,
) {
    fun toPosition(): Position? {
        val lat = latitude ?: return null
        val lon = longitude ?: return null
        val time = (dateTracker ?: dateServer)?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() } ?: return null
        val gps = (satellites ?: 0) > 0 || validSignal == true
        if (!gps && (radius ?: Double.MAX_VALUE) > MAX_CELL_RADIUS_M) return null
        return Position(time, lat, lon, uncertainty = radius, sensor = if (gps) WEENECT_GPS_SENSOR else WEENECT_CELL_SENSOR)
    }
}
