package app.mantrailing.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Ce qui est sauvegardé en ligne. Le nom est enregistré : ne pas renommer. */
@Serializable
enum class SyncKind { SESSION, TRAIL, DOG }

/**
 * Un élément tel qu'il circule entre le téléphone et le serveur : son contenu JSON (absent s'il a été supprimé)
 * et sa date de modification en millisecondes, qui départage deux versions (la plus récente gagne).
 */
data class SyncItem(
    val kind: SyncKind,
    val id: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val json: String? = null,
) {
    val key: String get() = keyOf(kind, id)
}

fun keyOf(kind: SyncKind, id: String) = "${kind.name}_$id"

/** Éléments reçus du serveur depuis [SyncState.cursor], et le nouveau curseur. */
data class PullResult(val items: List<SyncItem>, val cursor: String?)

/** Le serveur : Firebase aujourd'hui, une autre base (MySQL…) demain. */
interface SyncBackend {
    suspend fun push(items: List<SyncItem>)

    /** Éléments modifiés sur le serveur après [cursor] (tous si null). */
    suspend fun pull(cursor: String?): PullResult
}

/** Les données du téléphone vues par la synchronisation. */
interface LocalSyncStore {
    /** Éléments présents sur le téléphone. */
    suspend fun items(): List<SyncItem>

    /** Enregistre (ou supprime, si [SyncItem.deleted]) un élément reçu du serveur. */
    suspend fun apply(item: SyncItem)
}

/**
 * État de la synchronisation, gardé sur le téléphone : curseur du serveur, version envoyée de chaque élément,
 * et suppressions pas encore transmises.
 */
@Serializable
data class SyncState(
    val cursor: String? = null,
    val pushed: Map<String, Long> = emptyMap(),
    val tombstones: Map<String, Long> = emptyMap(),
    val lastSyncAt: Long? = null,
    /** Échec de la dernière tentative (effacé à la réussite suivante). */
    val lastError: String? = null,
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Fichier d'état de la synchronisation, partagé par l'appli et la tâche de fond (même processus). */
class SyncJournal(private val file: File) {
    suspend fun read(): SyncState = LOCK.withLock { readUnlocked() }

    /** Suppression faite sur le téléphone : elle sera transmise au serveur à la prochaine synchronisation. */
    suspend fun recordDeletion(kind: SyncKind, id: String, at: Long = System.currentTimeMillis()) = update {
        it.copy(tombstones = it.tombstones + (keyOf(kind, id) to at))
    }

    suspend fun update(change: (SyncState) -> SyncState) = LOCK.withLock { write(change(readUnlocked())) }

    /** Déconnexion : tout sera renvoyé au prochain compte connecté. */
    suspend fun reset() = LOCK.withLock { file.delete() }

    private fun readUnlocked(): SyncState =
        if (!file.exists()) SyncState()
        else runCatching { json.decodeFromString(SyncState.serializer(), file.readText()) }.getOrDefault(SyncState())

    private fun write(state: SyncState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(SyncState.serializer(), state))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        val LOCK = Mutex()
    }
}

data class SyncReport(val sent: Int, val received: Int)

/**
 * Synchronisation dans les deux sens, la version la plus récente de chaque élément l'emporte.
 * On reçoit d'abord (une version plus récente faite ailleurs n'est pas écrasée), puis on envoie.
 */
class SyncEngine(
    private val local: LocalSyncStore,
    private val backend: SyncBackend,
    private val journal: SyncJournal,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(): SyncReport = RUN.withLock {
        val state = journal.read()

        // 1. Réception.
        var received = 0
        val pulled = backend.pull(state.cursor)
        val localItems = local.items().associateBy { it.key }.toMutableMap()
        val knownRemote = mutableMapOf<String, Long>()
        val obsoleteDeletions = mutableSetOf<String>()
        for (remote in pulled.items) {
            val key = remote.key
            val mine = localItems[key]
            val deletedAt = state.tombstones[key]
            when {
                // Supprimé ici après cette version : notre suppression sera envoyée.
                deletedAt != null && deletedAt >= remote.updatedAt -> continue
                // Version d'ici plus récente (ou la même) : c'est elle qui sera envoyée si besoin.
                mine != null && mine.updatedAt >= remote.updatedAt -> continue
                else -> {
                    if (!(remote.deleted && mine == null)) {
                        local.apply(remote)
                        received++
                    }
                    if (remote.deleted) localItems.remove(key) else localItems[key] = remote
                    // Notre suppression est plus ancienne que cette version : la version du serveur l'emporte.
                    if (deletedAt != null) obsoleteDeletions += key
                }
            }
            knownRemote[key] = remote.updatedAt
        }
        journal.update {
            it.copy(cursor = pulled.cursor ?: it.cursor, pushed = it.pushed + knownRemote, tombstones = it.tombstones - obsoleteDeletions)
        }

        // 2. Envoi de ce qui a changé ici.
        val current = journal.read()
        val changed = localItems.values.filter { current.pushed[it.key] != it.updatedAt }
        val deletions = current.tombstones.map { (key, at) -> deletionOf(key, at) }.filterNotNull()
        val outgoing = changed + deletions
        if (outgoing.isNotEmpty()) backend.push(outgoing)
        journal.update { s ->
            s.copy(
                pushed = s.pushed + outgoing.associate { it.key to it.updatedAt },
                // Une suppression faite pendant l'envoi reste à envoyer.
                tombstones = s.tombstones.filter { (key, at) -> deletions.none { it.key == key && it.updatedAt == at } },
                lastSyncAt = now(),
                lastError = null,
            )
        }
        SyncReport(sent = outgoing.size, received = received)
    }

    private fun deletionOf(key: String, at: Long): SyncItem? {
        val kind = SyncKind.entries.find { key.startsWith(it.name + "_") } ?: return null
        return SyncItem(kind, key.removePrefix(kind.name + "_"), at, deleted = true)
    }

    private companion object {
        /** Une seule synchronisation à la fois (tâche de fond et bouton « Synchroniser maintenant »). */
        val RUN = Mutex()
    }
}
