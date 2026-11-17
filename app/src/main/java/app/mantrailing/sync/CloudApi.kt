package app.mantrailing.sync

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import app.mantrailing.BuildConfig
import app.mantrailing.data.SessionStore
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Connexion de l'appli refusée par le serveur (jeton expiré ou révoqué) : il faut se reconnecter. */
class CloudUnauthorizedException(message: String) : IOException(message)

/** Erreur renvoyée par le serveur, avec son message en français. */
class CloudException(val status: Int, message: String) : IOException(message)

/** Utilisateur connecté à la sauvegarde en ligne. */
@Serializable
data class CloudUser(val id: String, val email: String? = null, val name: String)

/**
 * Équipe : famille, club, binôme traceur / maître-chien. Ses membres voient ce qui est fait dans l'équipe,
 * et seulement ça : ce que chacun fait dans son espace perso ou dans une autre équipe leur reste invisible.
 * [members] : identifiant de chaque membre → son nom.
 */
@Serializable
data class Team(val id: String, val name: String, val ownerId: String = "", val members: Map<String, String> = emptyMap()) {
    val shared: Boolean get() = members.size > 1
}

@Serializable
data class SignInResult(val token: String, val user: CloudUser)

@Serializable
data class Me(val user: CloudUser, val teams: List<Team>)

@Serializable
private data class WireItem(val kind: String, val id: String, val updatedAt: Long, val deleted: Boolean, val json: String? = null)

@Serializable
private data class PushBody(val items: List<WireItem>)

@Serializable
private data class PullPage(val items: List<WireItem>, val cursor: Long, val more: Boolean = false)

@Serializable
private data class Invite(val code: String)

@Serializable
private data class ErrorBody(val error: String? = null)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
private val JSON = "application/json; charset=utf-8".toMediaType()

/**
 * API de la sauvegarde en ligne (serveur Mantrailing chez o2switch). [token] : jeton de l'appli,
 * obtenu à la connexion Google.
 */
class CloudApi(
    baseUrl: String,
    private val token: String? = null,
    private val client: OkHttpClient = defaultClient,
) {
    private val base = baseUrl.trimEnd('/').toHttpUrl()

    fun withToken(token: String) = CloudApi(base.toString(), token, client)

    suspend fun signInWithGoogle(idToken: String): SignInResult =
        call("POST", "auth/google", """{"idToken":${json.encodeToString(kotlinx.serialization.serializer<String>(), idToken)}}""")
            .let { json.decodeFromString(SignInResult.serializer(), it) }

    suspend fun logout() {
        call("POST", "auth/logout", "{}")
    }

    suspend fun me(): Me = json.decodeFromString(Me.serializer(), call("GET", "me"))

    suspend fun deleteAccount() {
        call("DELETE", "me")
    }

    suspend fun createTeam(name: String): Team =
        json.decodeFromString(Team.serializer(), call("POST", "teams", """{"name":${quote(name)}}"""))

    suspend fun createInvite(teamId: String): String =
        json.decodeFromString(Invite.serializer(), call("POST", "teams/$teamId/invites", "{}")).code

    /** Rejoint l'équipe du code : on reçoit ce qui s'y fait ; rien de ce que l'on a n'y est envoyé. */
    suspend fun join(code: String): Team =
        json.decodeFromString(Team.serializer(), call("POST", "teams/join", """{"code":${quote(code)}}"""))

    suspend fun leave(teamId: String) {
        call("DELETE", "teams/$teamId/members/me")
    }

    /** Éléments de l'espace [spaceId] (null : perso). */
    fun backend(spaceId: String?) = ApiBackend(this, spaceId ?: "perso")

    internal suspend fun call(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        // Chemin relatif à l'adresse de l'API, paramètres compris (« spaces/perso/items?after=12 »).
        val url = (base.toString().trimEnd('/') + "/" + path).toHttpUrl()
        val request = Request.Builder().url(url)
            .method(method, body?.toRequestBody(JSON) ?: if (method == "POST") "".toRequestBody(JSON) else null)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            when {
                response.isSuccessful -> text
                response.code == 401 -> throw CloudUnauthorizedException(errorMessage(text) ?: "Connexion expirée, reconnecte-toi.")
                else -> throw CloudException(response.code, errorMessage(text) ?: "Erreur du serveur (${response.code}).")
            }
        }
    }

    private fun errorMessage(text: String) = runCatching { json.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()

    private fun quote(value: String) = json.encodeToString(kotlinx.serialization.serializer<String>(), value)

    companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        internal fun encodePush(items: List<SyncItem>) =
            json.encodeToString(PushBody.serializer(), PushBody(items.map { WireItem(it.kind.name, it.id, it.updatedAt, it.deleted, it.json) }))

        internal fun decodePull(text: String): Triple<List<SyncItem>, Long, Boolean> {
            val page = json.decodeFromString(PullPage.serializer(), text)
            val items = page.items.mapNotNull { w ->
                SyncKind.entries.find { it.name == w.kind }?.let { SyncItem(it, w.id, w.updatedAt, w.deleted, w.json) }
            }
            return Triple(items, page.cursor, page.more)
        }

        internal fun decodeItem(text: String): SyncItem? {
            val w = json.decodeFromString(WireItem.serializer(), text)
            return SyncKind.entries.find { it.name == w.kind }?.let { SyncItem(it, w.id, w.updatedAt, w.deleted, w.json) }
        }
    }
}

/** Les éléments d'un espace sur le serveur ; le curseur est le numéro de la dernière écriture reçue. */
class ApiBackend(private val api: CloudApi, private val space: String) : SyncBackend {
    override suspend fun push(items: List<SyncItem>) {
        for (chunk in chunked(items)) api.call("POST", "spaces/$space/items", CloudApi.encodePush(chunk))
    }

    override suspend fun pull(cursor: String?): PullResult {
        var after = cursor?.toLongOrNull() ?: 0L
        val received = mutableListOf<SyncItem>()
        while (true) {
            val (items, next, more) = CloudApi.decodePull(api.call("GET", "spaces/$space/items?after=$after"))
            received += items
            after = next
            if (!more || items.isEmpty()) break
        }
        return PullResult(received, after.toString())
    }

    /** Un élément complet, pour afficher ce qui n'est plus sur le téléphone (null : introuvable). */
    suspend fun get(kind: SyncKind, id: String): SyncItem? = try {
        CloudApi.decodeItem(api.call("GET", "spaces/$space/items/${kind.name}/$id"))?.takeIf { !it.deleted }
    } catch (e: CloudException) {
        if (e.status == 404) null else throw e
    }

    companion object {
        /** Envois par lots : au plus 400 éléments et ~4 Mo (le serveur accepte 12 Mo par requête). */
        fun chunked(items: List<SyncItem>, maxCount: Int = 400, maxBytes: Int = 4_000_000): List<List<SyncItem>> {
            val chunks = mutableListOf<MutableList<SyncItem>>()
            var size = 0
            for (item in items) {
                val itemSize = (item.json?.length ?: 0) + 200
                val current = chunks.lastOrNull()
                if (current == null || current.size >= maxCount || (current.isNotEmpty() && size + itemSize > maxBytes)) {
                    chunks += mutableListOf(item)
                    size = itemSize
                } else {
                    current += item
                    size += itemSize
                }
            }
            return chunks
        }
    }
}

/** Configuration de la sauvegarde en ligne : adresse de l'API, fixée au build (gradle.properties). */
object CloudConfig {
    @Volatile
    private var overrideUrl: String? = null

    /** Tests : autre serveur (serveur local, faux serveur). */
    fun useServer(url: String?) {
        overrideUrl = url
    }

    val apiUrl: String get() = overrideUrl ?: BuildConfig.CLOUD_API_URL

    val isAvailable: Boolean get() = apiUrl.isNotEmpty() && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotEmpty()

    /** API avec le jeton du compte connecté (null : sauvegarde absente ou pas de compte). */
    suspend fun api(context: Context): CloudApi? {
        if (!isAvailable) return null
        val token = SessionStore(context).cloudToken.first() ?: return null
        return CloudApi(apiUrl, token)
    }
}

/** Codes d'invitation : lisibles au téléphone, saisis librement (minuscules, tirets, espaces). */
object InviteCodes {
    fun format(code: String) = code.chunked(4).joinToString("-")

    fun normalize(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }
}

/** Compte de sauvegarde : connexion Google (gestionnaire d'identifiants Android), puis jeton de l'API. */
object CloudAccount {
    suspend fun current(context: Context): CloudUser? = SessionStore(context).cloudUser.first()

    /** Choix du compte Google ; [activityContext] doit être une activité (fenêtre système de choix). */
    suspend fun signIn(activityContext: Context): CloudUser {
        if (!CloudConfig.isAvailable) error("Sauvegarde en ligne indisponible dans cette version.")
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val result = CredentialManager.create(activityContext)
            .getCredential(activityContext, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val idToken = GoogleIdTokenCredential.createFrom(result.credential.data).idToken
        val session = CloudApi(CloudConfig.apiUrl).signInWithGoogle(idToken)
        SessionStore(activityContext).saveCloudSession(session.token, session.user)
        return session.user
    }

    /** Déconnexion : jeton révoqué sur le serveur si possible, effacé du téléphone dans tous les cas. */
    suspend fun signOut(context: Context) {
        runCatching { CloudConfig.api(context)?.logout() }
        SessionStore(context).saveCloudSession(null, null)
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /** Supprime l'espace perso en ligne et le compte, et quitte les équipes (celles des autres gardent leurs données). */
    suspend fun deleteEverything(context: Context) {
        val api = CloudConfig.api(context) ?: return
        api.deleteAccount()
        SessionStore(context).saveCloudSession(null, null)
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
