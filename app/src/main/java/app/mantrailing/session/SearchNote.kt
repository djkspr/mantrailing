package app.mantrailing.session

import app.mantrailing.tractive.Position
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Familles de notes, avec la couleur de leur repère sur la carte : distinctes du départ (vert) et de l'arrivée
 * (rouge) de la piste, et de l'orange de la trace du chien.
 */
enum class NoteCategory(val label: String, val color: String) {
    DOG("Le chien", "#7048E8"),
    HANDLER("Le maître-chien", "#C2255C"),
    ENVIRONMENT("L'environnement", "#0C8599"),
    END("La fin", "#F59F00"),
    FREE("Note libre", "#495057"),
    /** Signalements du traceur pendant la pose : repère noir cerclé de jaune, comme un panneau d'alerte. */
    TRAILER("Le traceur", "#212529"),
}

/** Familles proposées au maître-chien pendant la recherche. */
val SEARCH_CATEGORIES = listOf(NoteCategory.DOG, NoteCategory.HANDLER, NoteCategory.ENVIRONMENT, NoteCategory.END)

/** Familles proposées au traceur pendant la pose : ses signalements, et l'environnement. */
val LAYING_CATEGORIES = listOf(NoteCategory.TRAILER, NoteCategory.ENVIRONMENT)

/** Ce qui peut arriver pendant une recherche. Le nom de chaque valeur est enregistré : ne pas les renommer. */
@Serializable
enum class NoteType(val label: String, val category: NoteCategory) {
    HESITATION("Hésitation", NoteCategory.DOG),
    TRACK_LOST("Perte de piste", NoteCategory.DOG),
    CIRCLING("Recherche en boucle", NoteCategory.DOG),
    TRACK_RESUMED("Reprise de piste", NoteCategory.DOG),
    NEGATIVE("Négatif", NoteCategory.DOG),
    JUNCTION_CHOICE("Choix à un carrefour", NoteCategory.DOG),
    SPEED_UP("Accélération", NoteCategory.DOG),
    NOSE_DOWN("Truffe au sol", NoteCategory.DOG),
    NOSE_UP("Truffe haute", NoteCategory.DOG),
    DISTRACTION("Distraction", NoteCategory.DOG),
    RELIEF("Besoin", NoteCategory.DOG),

    HANDLER_ERROR("Erreur de ma part", NoteCategory.HANDLER),
    LEASH("Problème de longe", NoteCategory.HANDLER),
    PAUSE("Pause / eau", NoteCategory.HANDLER),
    TRAINER_HELP("Aide du formateur", NoteCategory.HANDLER),

    SURFACE("Changement de surface", NoteCategory.ENVIRONMENT),
    WIND("Vent", NoteCategory.ENVIRONMENT),
    ROAD("Traversée de route", NoteCategory.ENVIRONMENT),
    OBSTACLE("Obstacle", NoteCategory.ENVIRONMENT),
    CONTAMINATION("Zone contaminée", NoteCategory.ENVIRONMENT),

    FOUND("Trouvé", NoteCategory.END),
    WRONG_ID("Mauvaise identification", NoteCategory.END),
    ABANDON("Abandon", NoteCategory.END),

    FREE("Note libre", NoteCategory.FREE),

    // Pendant la pose, pour prévenir le maître-chien.
    DANGER("Danger", NoteCategory.TRAILER),
    ATTENTION("Point d'attention", NoteCategory.TRAILER),
    TRAILER_STOP("Arrêt du traceur", NoteCategory.TRAILER),
    ;

    /** Signalement à montrer au maître-chien avant la recherche. */
    val isWarning: Boolean get() = this == DANGER || this == ATTENTION
}

/** Notes rapides pendant la pose de la piste. */
val LAYING_QUICK_NOTES = listOf(NoteType.DANGER, NoteType.ATTENTION, NoteType.ROAD, NoteType.SURFACE, NoteType.TRAILER_STOP)

/** Notes rapides proposées par défaut pendant la recherche (modifiables dans les paramètres). */
val DEFAULT_QUICK_NOTES = listOf(
    NoteType.HESITATION, NoteType.TRACK_LOST, NoteType.TRACK_RESUMED,
    NoteType.NEGATIVE, NoteType.DISTRACTION, NoteType.HANDLER_ERROR,
)
const val MAX_QUICK_NOTES = 6

fun formatQuickNotes(types: List<NoteType>): String = types.joinToString(",") { it.name }

/** Lecture tolérante : les noms inconnus (version future) sont ignorés, l'ordre est conservé. */
fun parseQuickNotes(value: String?): List<NoteType> =
    if (value == null) DEFAULT_QUICK_NOTES
    else value.split(',').mapNotNull { name -> NoteType.entries.find { it.name == name.trim() && it.category in SEARCH_CATEGORIES } }
        .distinct().take(MAX_QUICK_NOTES)

/**
 * Note posée pendant la recherche. [time] en secondes Unix ; la position est retrouvée sur la trace du chien
 * à la fin de la recherche (le collier n'envoie ses positions qu'après coup).
 */
@Serializable
data class SearchNote(
    val id: String,
    val time: Long,
    val type: NoteType,
    val comment: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
) {
    val label: String get() = if (type == NoteType.FREE) comment?.takeIf { it.isNotBlank() } ?: type.label else type.label

    /** Libellé avec la précision ajoutée (« Danger — chien au portail »). */
    val detail: String get() = comment?.takeIf { it.isNotBlank() && type != NoteType.FREE }?.let { "${type.label} — $it" } ?: label
}

/** Écart maximal entre l'heure d'une note et la position de la trace la plus proche dans le temps. */
const val NOTE_MAX_GAP_SECONDS = 120L

/** Place chaque note sur la position de la trace la plus proche dans le temps (à [NOTE_MAX_GAP_SECONDS] près). */
fun locateNotes(notes: List<SearchNote>, track: List<Position>): List<SearchNote> {
    if (track.isEmpty()) return notes
    return notes.map { note ->
        val nearest = track.minBy { abs(it.time - note.time) }
        if (abs(nearest.time - note.time) <= NOTE_MAX_GAP_SECONDS) note.copy(lat = nearest.lat, lon = nearest.lon) else note
    }
}
