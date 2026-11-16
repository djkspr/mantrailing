package app.mantrailing.sync

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import app.mantrailing.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable

/**
 * Projet Firebase de la sauvegarde en ligne. Configuré par app/google-services.json au build ;
 * les tests visent l'émulateur Firebase local ([useEmulator]).
 */
object CloudConfig {
    private const val APP_NAME = "mantrailing"
    private const val EMULATOR_PROJECT = "demo-mantrailing"

    @Volatile
    private var emulatorHost: String? = null
    private var app: FirebaseApp? = null

    /** Tests : émulateur Firebase (Auth 9099, Firestore 8080) sur [host], avant tout autre appel. */
    fun useEmulator(host: String) {
        emulatorHost = host
    }

    val isAvailable: Boolean get() = BuildConfig.FIREBASE_PROJECT_ID.isNotEmpty() || emulatorHost != null

    @Synchronized
    fun app(context: Context): FirebaseApp? {
        app?.let { return it }
        if (!isAvailable) return null
        val host = emulatorHost
        val options = if (host != null) {
            FirebaseOptions.Builder().setProjectId(EMULATOR_PROJECT).setApplicationId("1:000000000000:android:0000000000000000")
                .setApiKey("demo-api-key").build()
        } else {
            FirebaseOptions.Builder().setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY).build()
        }
        val created = FirebaseApp.getApps(context).find { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(context.applicationContext, options, APP_NAME)
        val firestore = FirebaseFirestore.getInstance(created)
        if (host != null) {
            firestore.useEmulator(host, 8080)
            FirebaseAuth.getInstance(created).useEmulator(host, 9099)
        }
        // Les données vivent dans les fichiers de l'appli : pas de second cache Firestore sur le téléphone.
        firestore.firestoreSettings = firestoreSettings { setLocalCacheSettings(memoryCacheSettings {}) }
        app = created
        return created
    }

    fun auth(context: Context): FirebaseAuth? = app(context)?.let { FirebaseAuth.getInstance(it) }

    fun firestore(context: Context): FirebaseFirestore? = app(context)?.let { FirebaseFirestore.getInstance(it) }
}

/**
 * Les éléments d'un espace dans Firestore, un document par élément (TYPE_id) :
 * users/{uid}/items pour l'espace perso, teams/{teamId}/items pour une équipe.
 * Le contenu est gardé tel quel (JSON), [FIELD_SYNCED_AT] (heure du serveur) sert de curseur de réception.
 */
class FirestoreBackend(private val firestore: FirebaseFirestore, private val items: CollectionReference) : SyncBackend {

    override suspend fun push(items: List<SyncItem>) {
        for (chunk in chunked(items)) {
            val batch = firestore.batch()
            chunk.forEach { item ->
                batch.set(
                    this.items.document(item.key),
                    mapOf(
                        "kind" to item.kind.name,
                        "id" to item.id,
                        "updatedAt" to item.updatedAt,
                        "deleted" to item.deleted,
                        "json" to item.json,
                        FIELD_SYNCED_AT to FieldValue.serverTimestamp(),
                    ),
                )
            }
            batch.commit().await()
        }
    }

    override suspend fun pull(cursor: String?): PullResult {
        val base = items.orderBy(FIELD_SYNCED_AT).orderBy(FieldPath.documentId()).let { q ->
            parseCursor(cursor)?.let { q.whereGreaterThan(FIELD_SYNCED_AT, it) } ?: q
        }
        val received = mutableListOf<SyncItem>()
        var last: DocumentSnapshot? = null
        while (true) {
            val page: Query = (last?.let { base.startAfter(it) } ?: base).limit(PAGE)
            val docs = page.get(Source.SERVER).await().documents
            docs.mapNotNullTo(received, ::toItem)
            if (docs.isEmpty()) break
            last = docs.last()
            if (docs.size < PAGE) break
        }
        return PullResult(received, last?.getTimestamp(FIELD_SYNCED_AT)?.let(::formatCursor) ?: cursor)
    }

    /** Un élément complet, pour afficher ce qui n'est plus sur le téléphone. */
    suspend fun get(kind: SyncKind, id: String): SyncItem? =
        items.document(keyOf(kind, id)).get(Source.SERVER).await().takeIf { it.exists() }?.let(::toItem)?.takeIf { !it.deleted }

    /** Efface tous les éléments de l'espace. */
    suspend fun deleteAll() {
        while (true) {
            val docs = items.limit(PAGE).get(Source.SERVER).await().documents
            if (docs.isEmpty()) return
            val batch = firestore.batch()
            docs.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    private fun toItem(doc: DocumentSnapshot): SyncItem? {
        val kind = doc.getString("kind")?.let { k -> SyncKind.entries.find { it.name == k } } ?: return null
        return SyncItem(
            kind = kind,
            id = doc.getString("id") ?: return null,
            updatedAt = doc.getLong("updatedAt") ?: return null,
            deleted = doc.getBoolean("deleted") ?: false,
            json = doc.getString("json"),
        )
    }

    companion object {
        const val FIELD_SYNCED_AT = "syncedAt"
        private const val PAGE = 200L

        /** Espace perso de [uid] (null) ou équipe [spaceId]. */
        fun of(firestore: FirebaseFirestore, uid: String, spaceId: String?): FirestoreBackend =
            FirestoreBackend(
                firestore,
                if (spaceId == null) firestore.collection("users").document(uid).collection("items")
                else firestore.collection("teams").document(spaceId).collection("items"),
            )

        /** Lots d'écriture : au plus 400 documents et ~4 Mo (limites Firestore : 500 et 10 Mo). */
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

        fun formatCursor(ts: Timestamp) = "${ts.seconds}:${ts.nanoseconds}"

        fun parseCursor(cursor: String?): Timestamp? {
            val (s, n) = cursor?.split(':')?.takeIf { it.size == 2 } ?: return null
            return Timestamp(s.toLongOrNull() ?: return null, n.toIntOrNull() ?: return null)
        }
    }
}

/**
 * Équipe : famille, club, binôme traceur / maître-chien. Ses membres voient ce qui est fait dans l'équipe,
 * et seulement ça : ce que chacun fait dans son espace perso ou dans une autre équipe leur reste invisible.
 */
@Serializable
data class Team(val id: String, val name: String, val ownerUid: String, val members: Map<String, String>) {
    val shared: Boolean get() = members.size > 1
}

/**
 * Équipes dans Firestore :
 * - users/{uid} : { teams: [teamId…] } — les équipes de chacun ;
 * - teams/{teamId} : { name, owner, members: { uid: nom } } ;
 * - invites/{code} : { teamId, createdBy, expiresAt } — code à transmettre pour rejoindre l'équipe.
 */
class TeamDirectory(private val firestore: FirebaseFirestore) {
    private fun userDoc(uid: String) = firestore.collection("users").document(uid)
    private fun teamDoc(teamId: String) = firestore.collection("teams").document(teamId)

    /** Équipes dont [uid] est membre ; celles quittées ou supprimées entre-temps sont oubliées. */
    suspend fun teamsOf(uid: String): List<Team> {
        @Suppress("UNCHECKED_CAST")
        val ids = (userDoc(uid).get(Source.SERVER).await().get("teams") as? List<String>).orEmpty()
        val teams = ids.mapNotNull { id -> runCatching { load(id) }.getOrNull()?.takeIf { uid in it.members } }
        if (teams.size != ids.size) userDoc(uid).set(mapOf("teams" to teams.map { it.id }), SetOptions.merge()).await()
        return teams
    }

    suspend fun load(teamId: String): Team? {
        val doc = teamDoc(teamId).get(Source.SERVER).await()
        if (!doc.exists()) return null
        @Suppress("UNCHECKED_CAST")
        val members = (doc.get("members") as? Map<String, String>).orEmpty()
        return Team(teamId, doc.getString("name") ?: "Équipe", doc.getString("owner") ?: "", members)
    }

    /** Nouvelle équipe, dont [uid] est le premier membre. */
    suspend fun create(uid: String, displayName: String, name: String): Team {
        val doc = firestore.collection("teams").document()
        val team = Team(doc.id, name.trim(), uid, mapOf(uid to displayName))
        doc.set(mapOf("name" to team.name, "owner" to uid, "members" to team.members)).await()
        userDoc(uid).set(mapOf("teams" to FieldValue.arrayUnion(team.id)), SetOptions.merge()).await()
        return team
    }

    /** Nouveau code d'invitation, valable [days] jours. */
    suspend fun createInvite(uid: String, teamId: String, days: Int = 7): String {
        val code = newInviteCode()
        val expires = Timestamp(java.util.Date(System.currentTimeMillis() + days * 24L * 3600 * 1000))
        firestore.collection("invites").document(code)
            .set(mapOf("teamId" to teamId, "createdBy" to uid, "expiresAt" to expires)).await()
        return code
    }

    /** Rejoint l'équipe du code [code]. Rien de ce que l'on a déjà n'y est envoyé. */
    suspend fun join(uid: String, displayName: String, code: String): Team {
        val normalized = normalizeInviteCode(code)
        val invite = firestore.collection("invites").document(normalized).get(Source.SERVER).await()
        val teamId = invite.getString("teamId") ?: error("Code d'invitation inconnu.")
        val expires = invite.getTimestamp("expiresAt")
        if (expires == null || expires.toDate().time < System.currentTimeMillis()) error("Ce code d'invitation a expiré.")
        teamDoc(teamId).update(mapOf("members.$uid" to displayName, "joinCode" to normalized)).await()
        userDoc(uid).set(mapOf("teams" to FieldValue.arrayUnion(teamId)), SetOptions.merge()).await()
        return load(teamId) ?: error("Équipe introuvable.")
    }

    /** Quitte l'équipe ; dernier membre, l'équipe et ses données sont supprimées. */
    suspend fun leave(uid: String, team: Team) {
        if (team.members.keys == setOf(uid)) {
            FirestoreBackend.of(firestore, uid, team.id).deleteAll()
            teamDoc(team.id).delete().await()
        } else {
            teamDoc(team.id).update("members.$uid", FieldValue.delete()).await()
        }
        userDoc(uid).set(mapOf("teams" to FieldValue.arrayRemove(team.id)), SetOptions.merge()).await()
    }

    /** Suppression du compte : espace perso effacé, équipes quittées (celles des autres gardent leurs données). */
    suspend fun deleteAccount(uid: String) {
        teamsOf(uid).forEach { leave(uid, it) }
        FirestoreBackend.of(firestore, uid, null).deleteAll()
        userDoc(uid).delete().await()
    }

    companion object {
        /** Sans 0/O ni 1/I/L, pour un code lu à voix haute ou recopié d'un SMS. */
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        fun newInviteCode(random: java.util.Random = java.security.SecureRandom()): String =
            (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

        /** Code saisi « abcd-efgh » ou « ABCD EFGH » : majuscules, sans séparateurs. */
        fun normalizeInviteCode(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }

        fun formatInviteCode(code: String) = code.chunked(4).joinToString("-")
    }
}

/** Compte de sauvegarde : connexion Google (gestionnaire d'identifiants Android) puis Firebase. */
object CloudAccount {
    fun email(context: Context): String? = CloudConfig.auth(context)?.currentUser?.let { it.email ?: it.uid }

    fun uid(context: Context): String? = CloudConfig.auth(context)?.currentUser?.uid

    /** Nom montré aux autres membres des équipes. */
    fun displayName(context: Context): String = CloudConfig.auth(context)?.currentUser?.let { user ->
        user.displayName?.takeIf { it.isNotBlank() } ?: user.email?.substringBefore('@')
    } ?: "Moi"

    /** Choix du compte Google ; [activityContext] doit être une activité (fenêtre système de choix). */
    suspend fun signIn(activityContext: Context): String {
        val auth = CloudConfig.auth(activityContext) ?: error("Sauvegarde en ligne indisponible dans cette version.")
        auth.signInWithCredential(googleCredential(activityContext)).await()
        return email(activityContext) ?: ""
    }

    suspend fun signOut(context: Context) {
        CloudConfig.auth(context)?.signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /**
     * Supprime l'espace perso en ligne, quitte les équipes et supprime le compte. Google redemande le compte :
     * Firebase exige une connexion récente pour supprimer un compte.
     */
    suspend fun deleteEverything(activityContext: Context) {
        val auth = CloudConfig.auth(activityContext) ?: return
        val user = auth.currentUser ?: return
        user.reauthenticate(googleCredential(activityContext)).await()
        TeamDirectory(CloudConfig.firestore(activityContext)!!).deleteAccount(user.uid)
        user.delete().await()
        signOut(activityContext)
    }

    private suspend fun googleCredential(activityContext: Context): AuthCredential {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.FIREBASE_WEB_CLIENT_ID).build()
        val result = CredentialManager.create(activityContext)
            .getCredential(activityContext, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val idToken = GoogleIdTokenCredential.createFrom(result.credential.data).idToken
        return GoogleAuthProvider.getCredential(idToken, null)
    }
}
