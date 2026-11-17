package app.mantrailing.session

import kotlinx.serialization.Serializable

/** Type de terrain d'une piste (plusieurs possibles). Enregistré par son nom : ne pas renommer les valeurs. */
@Serializable
enum class Terrain(val label: String) {
    FOREST("Forêt"),
    CITY("Ville"),
    COUNTRYSIDE("Campagne, champs"),
    PARK("Parc"),
    BUILDINGS("Bâtiments, zone industrielle"),
    MIXED("Mixte"),
}

/** Difficulté rencontrée sur la piste (plusieurs possibles). Enregistrée par son nom : ne pas renommer les valeurs. */
@Serializable
enum class Difficulty(val label: String) {
    CROSSINGS("Croisements"),
    CONTAMINATION("Contamination"),
    ROADS("Routes"),
    STRONG_WIND("Vent fort"),
    RAIN("Pluie"),
    HARD_GROUND("Sol dur, bitume"),
    WILDLIFE("Animaux, gibier"),
    CROWD("Foule"),
    OLD_TRAIL("Piste vieille"),
}

/** Objets odorants proposés, en plus de ceux déjà saisis. */
val SCENT_ARTICLE_SUGGESTIONS = listOf("T-shirt", "Chaussette", "Compresse", "Gant", "Objet personnel")

/**
 * Carnet d'entraînement d'une piste posée ou d'une recherche : tous les champs sont facultatifs.
 * - [runner] : le traceur (qui a posé la piste) ; [scentArticle] : l'objet odorant présenté au chien ;
 *   [place] : le lieu ; [terrain] et [difficulties] : listes de valeurs fixes, utilisables pour filtrer ou comparer.
 * - [reward] : récompense donnée ; [rating] : note du travail de l'équipe, de 1 à 5 (null : pas noté) ;
 *   [comment] : remarque libre.
 * Sur une piste posée, le traceur remplit surtout traceur, lieu, terrain, objet odorant et difficultés ;
 * la recherche faite sur cette piste reprend ces champs ([forSearch]) et le maître-chien complète le reste.
 */
@Serializable
data class TrainingLog(
    val runner: String? = null,
    val scentArticle: String? = null,
    val place: String? = null,
    val terrain: List<Terrain> = emptyList(),
    val difficulties: List<Difficulty> = emptyList(),
    val reward: String? = null,
    val rating: Int? = null,
    val comment: String? = null,
) {
    /** Rien de rempli : inutile de garder le carnet. */
    val isEmpty: Boolean
        get() = listOf(runner, scentArticle, place, reward, comment).all { it.isNullOrBlank() } &&
            terrain.isEmpty() && difficulties.isEmpty() && rating == null

    /** Ce que la piste transmet à la recherche faite dessus (pas la note ni la récompense, propres à la recherche). */
    fun forSearch(): TrainingLog = TrainingLog(runner, scentArticle, place, terrain, difficulties)

    /** Champs vides remplacés par null, note bornée à 1..5, doublons retirés ; null si tout est vide. */
    fun cleaned(): TrainingLog? {
        fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }
        val log = TrainingLog(
            runner.clean(), scentArticle.clean(), place.clean(), terrain.distinct(), difficulties.distinct(),
            reward.clean(), rating?.takeIf { it in 1..5 }, comment.clean(),
        )
        return log.takeUnless { it.isEmpty }
    }

    /** Résumé d'une ligne : « Traceur : Julie · Forêt, Ville · ★★★★☆ ». */
    val summary: String
        get() = buildList {
            runner?.let { add("Traceur : $it") }
            place?.let { add(it) }
            if (terrain.isNotEmpty()) add(terrain.joinToString { it.label })
            rating?.let { add(stars(it)) }
        }.joinToString(" · ")
}

/** « ★★★☆☆ » pour une note sur 5. */
fun stars(rating: Int): String = "★".repeat(rating.coerceIn(0, 5)) + "☆".repeat(5 - rating.coerceIn(0, 5))

/** Valeurs déjà saisies (traceurs, objets odorants…), les plus fréquentes d'abord, pour les proposer. */
fun logSuggestions(logs: List<TrainingLog?>, field: (TrainingLog) -> String?, extra: List<String> = emptyList()): List<String> {
    val used = logs.mapNotNull { it?.let(field)?.trim()?.takeIf(String::isNotEmpty) }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    return (used + extra.filter { e -> used.none { it.equals(e, ignoreCase = true) } }).distinctBy { it.lowercase() }
}
