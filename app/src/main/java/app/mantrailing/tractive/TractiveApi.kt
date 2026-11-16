package app.mantrailing.tractive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Session Tractive : le jeton remplace le mot de passe, qui n'est jamais conservé. */
data class Session(val userId: String, val accessToken: String, val expiresAt: Long)

data class Pet(val id: String, val name: String, val trackerId: String?)

/** Une position (collier ou téléphone). [time] en secondes Unix, [uncertainty] en mètres. */
@Serializable
data class Position(
    val time: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double? = null,
    val uncertainty: Double? = null,
    val sensor: String? = null,
)

open class TractiveException(message: String) : IOException(message)

/** Identifiants refusés ou session expirée : il faut se reconnecter. */
class TractiveAuthException(message: String) : TractiveException(message)

/**
 * Client de l'API de l'appli Tractive. Elle n'est pas publique ni documentée
 * (référence : la bibliothèque Python aiotractive) et peut changer sans prévenir.
 */
class TractiveApi(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun login(email: String, password: String): Session {
        val body = json.encodeToString(TokenRequest(email, password))
        val token = json.decodeFromJsonElement<TokenResponse>(call("POST", "auth/token", null, body))
        return Session(token.userId, token.accessToken, token.expiresAt)
    }

    suspend fun pets(session: Session): List<Pet> {
        val refs = json.decodeFromJsonElement<List<Ref>>(call("GET", "user/${session.userId}/trackable_objects", session))
        return refs.map { ref ->
            val obj = json.decodeFromJsonElement<TrackableObject>(call("GET", "trackable_object/${ref.id}", session))
            Pet(obj.id, obj.details?.name ?: obj.id, obj.deviceId)
        }
    }

    /** Positions entre [from] et [to], découpées en segments (trous de réception). */
    suspend fun positions(session: Session, trackerId: String, from: Instant, to: Instant): List<List<Position>> {
        val path = "tracker/$trackerId/positions?time_from=${from.epochSecond}&time_to=${to.epochSecond}&format=json_segments"
        return parseSegments(call("GET", path, session))
    }

    /** Active ou coupe le mode LIVE (une position toutes les 2-3 s). */
    suspend fun setLiveTracking(session: Session, trackerId: String, active: Boolean) {
        call("GET", "tracker/$trackerId/command/live_tracking/${if (active) "on" else "off"}", session)
    }

    private suspend fun call(method: String, path: String, session: Session?, body: String? = null): JsonElement =
        withContext(Dispatchers.IO) {
            val url = baseUrl.resolve(path) ?: throw TractiveException("URL invalide : $path")
            val request = Request.Builder().url(url)
                .header("x-tractive-client", CLIENT_ID)
                .header("accept", "application/json, text/plain, */*")
                .apply {
                    if (session != null) {
                        header("x-tractive-user", session.userId)
                        header("authorization", "Bearer ${session.accessToken}")
                    }
                }
                .method(method, body?.toRequestBody(JSON_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    response.code == 401 || response.code == 403 ->
                        throw TractiveAuthException("Accès refusé par Tractive (HTTP ${response.code})")
                    !response.isSuccessful ->
                        throw TractiveException("Tractive a répondu HTTP ${response.code} : ${text.take(200)}")
                    text.isBlank() -> JsonObject(emptyMap())
                    else -> json.parseToJsonElement(text)
                }
            }
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://graph.tractive.com/4/"
        const val CLIENT_ID = "625e533dc3c3b41c28a669f0"
        private val JSON_TYPE = "application/json;charset=UTF-8".toMediaType()
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Accepte une liste de segments, ou une simple liste de points (un seul segment). */
        internal fun parseSegments(element: JsonElement): List<List<Position>> {
            val array = element as? JsonArray ?: return emptyList()
            val segments = if (array.all { it is JsonArray }) array.map { it as JsonArray } else listOf(array)
            return segments
                .map { seg -> seg.mapNotNull { runCatching { json.decodeFromJsonElement<RawPosition>(it) }.getOrNull()?.toPosition() } }
                .map { seg -> seg.sortedBy { it.time } }
                .filter { it.isNotEmpty() }
        }
    }
}

@Serializable
private data class TokenRequest(
    @SerialName("platform_email") val email: String,
    @SerialName("platform_token") val password: String,
    @SerialName("grant_type") val grantType: String = "tractive",
)

@Serializable
private data class TokenResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_at") val expiresAt: Long = 0,
)

@Serializable
private data class Ref(@SerialName("_id") val id: String)

@Serializable
private data class TrackableObject(
    @SerialName("_id") val id: String,
    @SerialName("device_id") val deviceId: String? = null,
    val details: PetDetails? = null,
)

@Serializable
private data class PetDetails(val name: String? = null)

@Serializable
internal data class RawPosition(
    val time: Long,
    val latlong: List<Double>? = null,
    val alt: Double? = null,
    @SerialName("pos_uncertainty") val posUncertainty: Double? = null,
    @SerialName("sensor_used") val sensorUsed: String? = null,
) {
    fun toPosition(): Position? {
        val ll = latlong ?: return null
        if (ll.size < 2) return null
        return Position(time, ll[0], ll[1], alt, posUncertainty, sensorUsed)
    }
}
