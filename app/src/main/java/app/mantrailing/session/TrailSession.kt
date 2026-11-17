package app.mantrailing.session

import app.mantrailing.tractive.Position
import app.mantrailing.weather.Weather
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** D'où vient la trace du chien. */
@Serializable
enum class TrackSource { TRACTIVE, PHONE }

/** Ce qu'on enregistre : la piste posée par le traceur, ou la recherche du chien. */
enum class RecordingKind { TRAIL, SEARCH }

/** Enregistrement en cours (conservé pour survivre à l'arrêt de l'appli). */
data class ActiveRecording(
    val id: String,
    val dogName: String,
    val source: TrackSource,
    val startedAt: Long,
    val kind: RecordingKind = RecordingKind.SEARCH,
    /** Recherche : piste posée que le chien doit suivre. */
    val trailId: String? = null,
    /** Chien qui cherche (null : aucun chien enregistré dans l'appli). */
    val dogId: String? = null,
    /** Équipe qui verra la piste ou la recherche (null : espace perso). */
    val spaceId: String? = null,
)

/** Piste posée par le traceur : le parcours que le chien devra suivre. Horodatages en secondes Unix. */
@Serializable
data class LaidTrail(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val points: List<Position> = emptyList(),
    /** Dernière modification, en millisecondes (sauvegarde en ligne) ; absente avant elle. */
    val updatedAt: Long? = null,
    /** Parcours retiré du téléphone pour gagner de la place : il n'est plus qu'en ligne. */
    val archived: Boolean = false,
    /** Longueur, gardée quand le parcours n'est plus qu'en ligne. */
    val lengthMeters: Double? = null,
    /** Équipe qui voit cette piste (null : espace perso, visible de soi seul). */
    val spaceId: String? = null,
    /** Signalements du traceur pendant la pose (danger, point d'attention…), placés sur le parcours. */
    val notes: List<SearchNote> = emptyList(),
    /** Météo au début de la pose (null : pas encore récupérée). */
    val weather: Weather? = null,
) {
    /** Dangers et points d'attention à annoncer au maître-chien avant la recherche. */
    val warnings: List<SearchNote> get() = notes.filter { it.type.isWarning }

    /** Version pour la sauvegarde en ligne : la plus récente l'emporte. */
    val version: Long get() = updatedAt ?: (endedAt * 1000)
}

/** Recherche terminée : trace du chien, et la piste suivie s'il y en a une. Horodatages en secondes Unix. */
@Serializable
data class TrailSession(
    val id: String,
    val dogName: String,
    val source: TrackSource,
    val startedAt: Long,
    val endedAt: Long,
    val segments: List<List<Position>> = emptyList(),
    val trailId: String? = null,
    /** Copie de la piste posée : la recherche reste complète même si la piste est supprimée. */
    val trail: List<Position> = emptyList(),
    /** Fin de la pose de la piste, pour calculer son âge au départ du chien. */
    val trailEndedAt: Long? = null,
    /** Notes posées pendant la recherche. */
    val notes: List<SearchNote> = emptyList(),
    /** Chien qui a cherché ; absent des recherches d'avant les chiens multiples (reconnues à [dogName]). */
    val dogId: String? = null,
    /** Dernière modification, en millisecondes (sauvegarde en ligne) ; absente avant elle. */
    val updatedAt: Long? = null,
    /** Trace et piste retirées du téléphone pour gagner de la place : elles ne sont plus qu'en ligne. */
    val archived: Boolean = false,
    /** Bilan gardé quand la trace n'est plus qu'en ligne. */
    val summary: SessionSummary? = null,
    /** Équipe qui voit cette recherche (null : espace perso, visible de soi seul). */
    val spaceId: String? = null,
    /** Copie des signalements du traceur sur la piste suivie. */
    val trailNotes: List<SearchNote> = emptyList(),
    /** Météo au départ du chien (null : pas encore récupérée). */
    val weather: Weather? = null,
    /** Météo pendant la pose de la piste suivie, pour comparer. */
    val trailWeather: Weather? = null,
) {
    /** Âge de la piste au départ du chien, en secondes. */
    val trailAgeSeconds: Long? get() = trailEndedAt?.let { (startedAt - it).coerceAtLeast(0) }

    /** Version pour la sauvegarde en ligne : la plus récente l'emporte. */
    val version: Long get() = updatedAt ?: (endedAt * 1000)
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Éléments enregistrés un par fichier JSON dans [dir] (stockage privé de l'appli). */
open class JsonDirRepository<T>(
    private val dir: File,
    private val serializer: KSerializer<T>,
    private val idOf: (T) -> String,
    private val dateOf: (T) -> Long,
) {
    private fun file(id: String) = File(dir, "$id.json")

    fun save(item: T) {
        dir.mkdirs()
        val id = idOf(item)
        // Écriture atomique : un fichier à moitié écrit ne remplace jamais un élément valide.
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(json.encodeToString(serializer, item))
        if (!tmp.renameTo(file(id))) {
            file(id).delete()
            tmp.renameTo(file(id))
        }
    }

    fun load(id: String): T? = file(id).takeIf { it.exists() }?.let { decode(it) }

    /** Tous les éléments, le plus récent d'abord ; les fichiers illisibles sont ignorés. */
    fun list(): List<T> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull(::decode)
            .sortedByDescending(dateOf)

    fun delete(id: String) {
        file(id).delete()
    }

    private fun decode(f: File): T? = runCatching { json.decodeFromString(serializer, f.readText()) }.getOrNull()
}

/** Recherches enregistrées. */
class SessionRepository(dir: File) : JsonDirRepository<TrailSession>(dir, TrailSession.serializer(), { it.id }, { it.startedAt })

/** Pistes posées. */
class LaidTrailRepository(dir: File) : JsonDirRepository<LaidTrail>(dir, LaidTrail.serializer(), { it.id }, { it.startedAt })

/** Pistes proposées au départ d'une recherche : posées depuis moins de [maxAgeSeconds] et pas encore travaillées. */
fun availableTrails(trails: List<LaidTrail>, sessions: List<TrailSession>, now: Long, maxAgeSeconds: Long = 48 * 3600): List<LaidTrail> {
    val used = sessions.mapNotNull { it.trailId }.toSet()
    return trails.filter { it.id !in used && it.points.isNotEmpty() && now - it.endedAt <= maxAgeSeconds }
}

/**
 * Éléments ajoutés un par ligne pendant un enregistrement :
 * rien n'est perdu si l'appli est arrêtée en cours de route.
 */
open class JsonLinesBuffer<T>(private val file: File, private val serializer: KSerializer<T>) {
    fun append(item: T) {
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(serializer, item) + "\n")
    }

    /** Les lignes illisibles (arrêt brutal pendant une écriture) sont ignorées. */
    fun read(): List<T> =
        if (!file.exists()) emptyList()
        else file.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    /** Retire le dernier élément ajouté et le renvoie. */
    fun removeLast(): T? {
        val items = read()
        val last = items.lastOrNull() ?: return null
        clear()
        items.dropLast(1).forEach(::append)
        return last
    }

    fun clear() {
        file.delete()
    }
}

/** Positions du téléphone pendant l'enregistrement. */
class RecordingBuffer(file: File) : JsonLinesBuffer<Position>(file, Position.serializer())

/** Notes posées pendant la recherche en cours. */
class NotesBuffer(file: File) : JsonLinesBuffer<SearchNote>(file, SearchNote.serializer())

/** Source utilisée au démarrage d'une piste. */
sealed interface StartDecision {
    data class Start(val source: TrackSource) : StartDecision
    /** Collier connecté mais aucun choix enregistré : on propose le collier. */
    data object AskSource : StartDecision
}

fun decideSource(preferred: TrackSource?, tractiveConnected: Boolean): StartDecision = when {
    !tractiveConnected -> StartDecision.Start(TrackSource.PHONE)
    preferred == null -> StartDecision.AskSource
    else -> StartDecision.Start(preferred)
}

/** Positions GPS du téléphone trop imprécises pour suivre un chien : écartées. */
const val MAX_PHONE_ACCURACY_M = 30f

fun acceptPhoneFix(accuracyMeters: Float?): Boolean = accuracyMeters != null && accuracyMeters <= MAX_PHONE_ACCURACY_M
