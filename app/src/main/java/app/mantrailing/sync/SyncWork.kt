package app.mantrailing.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mantrailing.data.AppStorage
import app.mantrailing.data.SessionStore
import app.mantrailing.session.Dog
import app.mantrailing.session.LaidTrail
import app.mantrailing.session.TrailSession
import app.mantrailing.session.archived
import app.mantrailing.session.sessionsToArchive
import app.mantrailing.session.trailsToArchive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Recherches, pistes posées et chiens d'un espace ([spaceId] : équipe, null : perso), vus par la synchronisation.
 * Un élément d'un autre espace n'est ni envoyé ni touché ici.
 */
class AppSyncStore(context: Context, private val spaceId: String?) : LocalSyncStore {
    private val sessions = AppStorage.sessions(context)
    private val trails = AppStorage.trails(context)
    private val store = SessionStore(context)

    // Éléments qui ne sont plus qu'en ligne : jamais renvoyés (ils écraseraient la version complète du serveur).
    override suspend fun items(): List<SyncItem> =
        sessions.list().filter { !it.archived && it.spaceId == spaceId }
            .map { SyncItem(SyncKind.SESSION, it.id, it.version, json = json.encodeToString(TrailSession.serializer(), it)) } +
            trails.list().filter { !it.archived && it.spaceId == spaceId }
                .map { SyncItem(SyncKind.TRAIL, it.id, it.version, json = json.encodeToString(LaidTrail.serializer(), it)) } +
            store.dogs.first().filter { it.spaceId == spaceId }
                .map { SyncItem(SyncKind.DOG, it.id, it.updatedAt, json = json.encodeToString(Dog.serializer(), it)) }

    override suspend fun apply(item: SyncItem) {
        when (item.kind) {
            SyncKind.SESSION -> {
                val local = sessions.load(item.id)
                if (item.deleted) { if (local?.spaceId == spaceId) sessions.delete(item.id) }
                else sessions.save(decode(item, TrailSession.serializer()).copy(spaceId = spaceId))
            }
            SyncKind.TRAIL -> {
                val local = trails.load(item.id)
                if (item.deleted) { if (local?.spaceId == spaceId) trails.delete(item.id) }
                else trails.save(decode(item, LaidTrail.serializer()).copy(spaceId = spaceId))
            }
            SyncKind.DOG -> {
                val all = store.dogs.first()
                val local = all.find { it.id == item.id }
                // Suppression d'un chien passé depuis dans un autre espace : il reste.
                if (item.deleted && local != null && local.spaceId != spaceId) return
                val others = all.filter { it.id != item.id }
                val updated = if (item.deleted) others else others + decode(item, Dog.serializer()).copy(spaceId = spaceId)
                val active = store.activeDogId.first()?.takeIf { id -> updated.any { it.id == id } } ?: updated.firstOrNull()?.id
                store.saveDogs(updated, active)
            }
        }
    }

    /**
     * Libère de la place : ce qui est terminé depuis plus de [keepDays] jours et confirmé par le serveur
     * ne garde qu'un résumé sur le téléphone. Renvoie le nombre d'éléments allégés.
     */
    suspend fun archiveOld(state: SyncState, keepDays: Int, nowSeconds: Long = System.currentTimeMillis() / 1000): Int {
        val mine = sessions.list().filter { it.spaceId == spaceId }
        val myTrails = trails.list().filter { it.spaceId == spaceId }
        val oldSessions = sessionsToArchive(mine, { state.pushed[keyOf(SyncKind.SESSION, it.id)] }, nowSeconds, keepDays)
        val oldTrails = trailsToArchive(myTrails, { state.pushed[keyOf(SyncKind.TRAIL, it.id)] }, nowSeconds, keepDays)
        oldSessions.forEach { sessions.save(archived(it)) }
        oldTrails.forEach { trails.save(archived(it)) }
        return oldSessions.size + oldTrails.size
    }

    private fun <T> decode(item: SyncItem, serializer: kotlinx.serialization.KSerializer<T>): T =
        json.decodeFromString(serializer, item.json ?: error("${item.key} sans contenu"))

    companion object {
        fun decodeSession(item: SyncItem): TrailSession = json.decodeFromString(TrailSession.serializer(), item.json!!)
        fun decodeTrail(item: SyncItem): LaidTrail = json.decodeFromString(LaidTrail.serializer(), item.json!!)

        /** Équipe quittée (ou dont on a été retiré) : ses données n'ont plus à rester sur le téléphone. */
        suspend fun forgetSpace(context: Context, spaceId: String) {
            val sessions = AppStorage.sessions(context)
            val trails = AppStorage.trails(context)
            val store = SessionStore(context)
            sessions.list().filter { it.spaceId == spaceId }.forEach { sessions.delete(it.id) }
            trails.list().filter { it.spaceId == spaceId }.forEach { trails.delete(it.id) }
            val dogs = store.dogs.first().filter { it.spaceId != spaceId }
            store.saveDogs(dogs, store.activeDogId.first()?.takeIf { id -> dogs.any { it.id == id } } ?: dogs.firstOrNull()?.id)
            AppStorage.syncJournal(context, spaceId).reset()
        }
    }
}

/** État de la sauvegarde pour l'écran : synchronisation en cours, et nombre de synchronisations terminées. */
object SyncEvents {
    /** Plusieurs tâches peuvent se chevaucher (régulière, après un changement, à la demande). */
    data class Status(val runningCount: Int = 0, val completed: Int = 0) {
        val running: Boolean get() = runningCount > 0
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun started() = _status.update { it.copy(runningCount = it.runningCount + 1) }
    fun finished() = _status.update { it.copy(runningCount = (it.runningCount - 1).coerceAtLeast(0), completed = it.completed + 1) }
}

/** Une synchronisation complète : équipes à jour, puis chaque espace (perso et équipes), puis place libérée. */
object CloudSync {
    suspend fun run(context: Context) {
        val api = CloudConfig.api(context) ?: return
        val store = SessionStore(context)

        // Équipes : nouvelles (rejointes ailleurs avec le même compte), et celles quittées ou dont on a été retiré.
        val known = store.cloudTeams.first()
        val teams = api.me().teams
        known.filter { old -> teams.none { it.id == old.id } }.forEach { AppSyncStore.forgetSpace(context, it.id) }
        store.saveCloudTeams(teams)

        val keepDays = store.keepOnPhoneDays.first()
        for (spaceId in listOf<String?>(null) + teams.map { it.id }) {
            val local = AppSyncStore(context, spaceId)
            val journal = AppStorage.syncJournal(context, spaceId)
            SyncEngine(local, api.backend(spaceId), journal).sync()
            if (keepDays != null) local.archiveOld(journal.read(), keepDays)
        }
    }
}

/** Synchronisation en tâche de fond, lancée par WorkManager quand le réseau demandé est là. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (CloudConfig.api(context) == null) return Result.success()
        val journal = AppStorage.syncJournal(context, null)
        SyncEvents.started()
        return try {
            CloudSync.run(context)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudUnauthorizedException) {
            // Jeton refusé (compte supprimé ailleurs, jeton révoqué) : inutile d'insister, il faut se reconnecter.
            SessionStore(context).saveCloudSession(null, null)
            journal.update { it.copy(lastError = "Connexion expirée : réactive la sauvegarde en ligne.") }
            Result.failure()
        } catch (e: Exception) {
            journal.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } finally {
            SyncEvents.finished()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}

/** Quand synchroniser : après chaque changement, toutes les heures, et à la demande. */
object SyncScheduler {
    private const val PERIODIC = "sync-periodic"
    private const val SOON = "sync-soon"
    private const val NOW = "sync-now"

    fun constraints(wifiOnly: Boolean): Constraints =
        Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

    /** Compte connecté : synchronisation régulière, et une tout de suite. */
    fun enable(context: Context, wifiOnly: Boolean) {
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints(wifiOnly))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        syncSoon(context, wifiOnly)
    }

    /** Après un changement sur le téléphone ; attend le Wi-Fi si demandé. */
    fun syncSoon(context: Context, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(wifiOnly))
            .setInitialDelay(5, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** « Synchroniser maintenant » : demandé explicitement, donc sur n'importe quel réseau. */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(wifiOnly = false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(SOON)
            cancelUniqueWork(NOW)
        }
    }
}
