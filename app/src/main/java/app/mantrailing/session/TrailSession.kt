package app.mantrailing.session

import app.mantrailing.tractive.Position
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** D'où vient la trace du chien. */
@Serializable
enum class TrackSource { TRACTIVE, PHONE }

/** Piste en cours d'enregistrement (conservée pour survivre à l'arrêt de l'appli). */
data class ActiveRecording(val id: String, val dogName: String, val source: TrackSource, val startedAt: Long)

/** Piste terminée. Horodatages en secondes Unix. */
@Serializable
data class TrailSession(
    val id: String,
    val dogName: String,
    val source: TrackSource,
    val startedAt: Long,
    val endedAt: Long,
    val segments: List<List<Position>> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Pistes enregistrées, une par fichier JSON dans [dir] (stockage privé de l'appli). */
class SessionRepository(private val dir: File) {
    private fun file(id: String) = File(dir, "$id.json")

    fun save(session: TrailSession) {
        dir.mkdirs()
        // Écriture atomique : un fichier à moitié écrit ne remplace jamais une piste valide.
        val tmp = File(dir, "${session.id}.json.tmp")
        tmp.writeText(json.encodeToString(session))
        if (!tmp.renameTo(file(session.id))) {
            file(session.id).delete()
            tmp.renameTo(file(session.id))
        }
    }

    fun load(id: String): TrailSession? = file(id).takeIf { it.exists() }?.let { runCatching { json.decodeFromString<TrailSession>(it.readText()) }.getOrNull() }

    /** Toutes les pistes, la plus récente d'abord ; les fichiers illisibles sont ignorés. */
    fun list(): List<TrailSession> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { json.decodeFromString<TrailSession>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.startedAt }

    fun delete(id: String) {
        file(id).delete()
    }
}

/**
 * Positions du téléphone pendant l'enregistrement, ajoutées une par ligne :
 * rien n'est perdu si l'appli est arrêtée en cours de piste.
 */
class RecordingBuffer(private val file: File) {
    fun append(position: Position) {
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(position) + "\n")
    }

    fun read(): List<Position> =
        if (!file.exists()) emptyList()
        else file.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { json.decodeFromString<Position>(it) }.getOrNull() }

    fun clear() {
        file.delete()
    }
}

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
