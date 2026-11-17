package app.mantrailing.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.mantrailing.session.ActiveRecording
import app.mantrailing.sync.CloudUser
import app.mantrailing.sync.Team
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import app.mantrailing.session.DEFAULT_KEEP_ON_PHONE_DAYS
import app.mantrailing.session.Dog
import app.mantrailing.session.NoteType
import app.mantrailing.session.RecordingKind
import app.mantrailing.session.TrackSource
import app.mantrailing.session.formatDogs
import app.mantrailing.session.tractiveDogId
import app.mantrailing.session.formatQuickNotes
import app.mantrailing.session.parseDogs
import app.mantrailing.session.parseQuickNotes
import app.mantrailing.tractive.Session
import app.mantrailing.weenect.WeenectSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "session")
private val json = Json { ignoreUnknownKeys = true }

/**
 * Préférences de l'appli, dans son stockage privé : jeton Tractive (jamais le mot de passe), chiens,
 * source de la trace du chien, enregistrement en cours.
 */
class SessionStore(private val context: Context) {
    /** Compte Tractive connecté. */
    val tractive: Flow<Session?> = context.dataStore.data.map { p ->
        val userId = p[USER_ID] ?: return@map null
        val token = p[TOKEN] ?: return@map null
        Session(userId, token, p[EXPIRES_AT] ?: 0)
    }

    /** Compte Weenect connecté. */
    val weenect: Flow<WeenectSession?> = context.dataStore.data.map { p -> p[WEENECT_TOKEN]?.let(::WeenectSession) }

    /** Chiens de l'équipe ; avant les chiens multiples, le chien Tractive choisi devient le premier. */
    val dogs: Flow<List<Dog>> = context.dataStore.data.map { p -> p[DOGS]?.let(::parseDogs) ?: legacyDog(p) }

    /** Chien sélectionné sur l'accueil. */
    val activeDogId: Flow<String?> = context.dataStore.data.map { p -> 
        p[ACTIVE_DOG] ?: if (p[DOGS] == null) p[PET_ID]?.let(::legacyDogId) else null
    }

    /** Animaux Tractive retirés de l'appli : pas réimportés à la prochaine synchronisation. */
    val ignoredPetIds: Flow<Set<String>> = context.dataStore.data.map { p ->
        p[IGNORED_PETS]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    private fun legacyDog(p: Preferences): List<Dog> =
        p[PET_ID]?.let { listOf(Dog(legacyDogId(it), p[PET_NAME] ?: it, it, p[TRACKER_ID])) } ?: emptyList()

    private fun legacyDogId(petId: String) = tractiveDogId(petId)

    suspend fun saveDogs(dogs: List<Dog>, activeDogId: String?) {
        context.dataStore.edit {
            it[DOGS] = formatDogs(dogs)
            if (activeDogId != null) it[ACTIVE_DOG] = activeDogId else it.remove(ACTIVE_DOG)
            listOf(PET_ID, PET_NAME, TRACKER_ID).forEach(it::remove)
        }
    }

    suspend fun saveIgnoredPetIds(ids: Set<String>) {
        context.dataStore.edit { it[IGNORED_PETS] = ids.joinToString(",") }
    }

    /** Sauvegarde en ligne seulement en Wi-Fi (par défaut : pas de données mobiles). */
    val syncWifiOnly: Flow<Boolean> = context.dataStore.data.map { p -> p[SYNC_WIFI_ONLY] ?: true }

    suspend fun saveSyncWifiOnly(wifiOnly: Boolean) {
        context.dataStore.edit { it[SYNC_WIFI_ONLY] = wifiOnly }
    }

    /** Jours gardés en entier sur le téléphone quand la sauvegarde est active (null : tout garder). */
    val keepOnPhoneDays: Flow<Int?> = context.dataStore.data.map { p ->
        when (val v = p[KEEP_DAYS]) {
            null -> DEFAULT_KEEP_ON_PHONE_DAYS
            "all" -> null
            else -> v.toIntOrNull() ?: DEFAULT_KEEP_ON_PHONE_DAYS
        }
    }

    suspend fun saveKeepOnPhoneDays(days: Int?) {
        context.dataStore.edit { it[KEEP_DAYS] = days?.toString() ?: "all" }
    }

    /** Équipes du compte connecté, telles que lues au serveur à la dernière synchronisation. */
    val cloudTeams: Flow<List<Team>> = context.dataStore.data.map { p ->
        p[CLOUD_TEAMS]?.let { runCatching { json.decodeFromString(ListSerializer(Team.serializer()), it) }.getOrNull() } ?: emptyList()
    }

    suspend fun saveCloudTeams(teams: List<Team>) {
        context.dataStore.edit { it[CLOUD_TEAMS] = json.encodeToString(ListSerializer(Team.serializer()), teams) }
    }

    /** Jeton de l'API de sauvegarde (jamais le mot de passe Google : il ne passe pas par l'appli). */
    val cloudToken: Flow<String?> = context.dataStore.data.map { p -> p[CLOUD_TOKEN] }

    /** Compte de sauvegarde connecté. */
    val cloudUser: Flow<CloudUser?> = context.dataStore.data.map { p ->
        p[CLOUD_USER]?.let { runCatching { json.decodeFromString(CloudUser.serializer(), it) }.getOrNull() }
    }

    suspend fun saveCloudSession(token: String?, user: CloudUser?) {
        context.dataStore.edit {
            if (token != null && user != null) {
                it[CLOUD_TOKEN] = token
                it[CLOUD_USER] = json.encodeToString(CloudUser.serializer(), user)
            } else {
                it.remove(CLOUD_TOKEN)
                it.remove(CLOUD_USER)
            }
        }
    }

    /** Espace choisi pour la dernière piste ou recherche (null : perso), proposé la fois suivante. */
    val lastSpaceId: Flow<String?> = context.dataStore.data.map { p -> p[LAST_SPACE]?.takeIf { it.isNotEmpty() } }

    suspend fun saveLastSpaceId(spaceId: String?) {
        context.dataStore.edit { it[LAST_SPACE] = spaceId ?: "" }
    }

    /** Source choisie pour la trace du chien, null tant que l'utilisateur n'a pas choisi. */
    val trackSource: Flow<TrackSource?> = context.dataStore.data.map { p ->
        p[TRACK_SOURCE]?.let { runCatching { TrackSource.valueOf(it) }.getOrNull() }
    }

    /** Notes rapides affichées pendant la recherche. */
    val quickNotes: Flow<List<NoteType>> = context.dataStore.data.map { p -> parseQuickNotes(p[QUICK_NOTES]) }

    suspend fun saveQuickNotes(types: List<NoteType>) {
        context.dataStore.edit { it[QUICK_NOTES] = formatQuickNotes(types) }
    }

    val activeRecording: Flow<ActiveRecording?> = context.dataStore.data.map { p ->
        val id = p[REC_ID] ?: return@map null
        val source = p[REC_SOURCE]?.let { runCatching { TrackSource.valueOf(it) }.getOrNull() } ?: return@map null
        val kind = p[REC_KIND]?.let { runCatching { RecordingKind.valueOf(it) }.getOrNull() } ?: RecordingKind.SEARCH
        ActiveRecording(id, p[REC_DOG] ?: "", source, p[REC_STARTED] ?: 0, kind, p[REC_TRAIL], p[REC_DOG_ID], p[REC_SPACE])
    }

    suspend fun saveSession(session: Session) {
        context.dataStore.edit {
            it[USER_ID] = session.userId
            it[TOKEN] = session.accessToken
            it[EXPIRES_AT] = session.expiresAt
        }
    }

    suspend fun saveWeenect(session: WeenectSession?) {
        context.dataStore.edit { if (session == null) it.remove(WEENECT_TOKEN) else it[WEENECT_TOKEN] = session.token }
    }

    suspend fun saveTrackSource(source: TrackSource) {
        context.dataStore.edit { it[TRACK_SOURCE] = source.name }
    }

    suspend fun saveActiveRecording(recording: ActiveRecording?) {
        context.dataStore.edit {
            if (recording == null) {
                listOf(REC_ID, REC_DOG, REC_SOURCE, REC_KIND, REC_TRAIL, REC_DOG_ID, REC_SPACE).forEach(it::remove)
                it.remove(REC_STARTED)
            } else {
                it[REC_ID] = recording.id
                it[REC_DOG] = recording.dogName
                it[REC_SOURCE] = recording.source.name
                it[REC_STARTED] = recording.startedAt
                it[REC_KIND] = recording.kind.name
                if (recording.trailId != null) it[REC_TRAIL] = recording.trailId else it.remove(REC_TRAIL)
                if (recording.dogId != null) it[REC_DOG_ID] = recording.dogId else it.remove(REC_DOG_ID)
                if (recording.spaceId != null) it[REC_SPACE] = recording.spaceId else it.remove(REC_SPACE)
            }
        }
    }

    /** Déconnexion Tractive : efface le jeton ; les chiens, préférences et l'enregistrement en cours restent. */
    suspend fun clearTractive() {
        context.dataStore.edit { p ->
            listOf(USER_ID, TOKEN).forEach(p::remove)
            p.remove(EXPIRES_AT)
        }
    }

    private companion object {
        val USER_ID = stringPreferencesKey("user_id")
        val TOKEN = stringPreferencesKey("access_token")
        val WEENECT_TOKEN = stringPreferencesKey("weenect_token")
        val EXPIRES_AT = longPreferencesKey("expires_at")
        // Chien Tractive unique, avant les chiens multiples : lu une fois pour la migration.
        val PET_ID = stringPreferencesKey("pet_id")
        val PET_NAME = stringPreferencesKey("pet_name")
        val TRACKER_ID = stringPreferencesKey("tracker_id")
        val DOGS = stringPreferencesKey("dogs")
        val ACTIVE_DOG = stringPreferencesKey("active_dog_id")
        val IGNORED_PETS = stringPreferencesKey("ignored_tractive_pets")
        val TRACK_SOURCE = stringPreferencesKey("track_source")
        val REC_ID = stringPreferencesKey("recording_id")
        val REC_DOG = stringPreferencesKey("recording_dog")
        val REC_DOG_ID = stringPreferencesKey("recording_dog_id")
        val REC_SPACE = stringPreferencesKey("recording_space_id")
        val REC_SOURCE = stringPreferencesKey("recording_source")
        val REC_STARTED = longPreferencesKey("recording_started_at")
        val REC_KIND = stringPreferencesKey("recording_kind")
        val REC_TRAIL = stringPreferencesKey("recording_trail_id")
        val QUICK_NOTES = stringPreferencesKey("quick_notes")
        val SYNC_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val CLOUD_TEAMS = stringPreferencesKey("cloud_teams")
        val CLOUD_TOKEN = stringPreferencesKey("cloud_token")
        val CLOUD_USER = stringPreferencesKey("cloud_user")
        val LAST_SPACE = stringPreferencesKey("last_space_id")
        val KEEP_DAYS = stringPreferencesKey("keep_on_phone_days")
    }
}
