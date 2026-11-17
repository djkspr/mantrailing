package app.mantrailing.session

import app.mantrailing.track.TrackStats
import app.mantrailing.track.TrailDeviation
import app.mantrailing.track.pathLengthMeters
import kotlinx.serialization.Serializable

/**
 * Résumé gardé sur le téléphone d'une recherche dont la trace n'est plus qu'en ligne :
 * de quoi l'afficher dans « Mes pistes » sans la télécharger.
 */
@Serializable
data class SessionSummary(
    val distanceMeters: Double,
    val pointCount: Int,
    val meanDeviationMeters: Double? = null,
    val reachedEnd: Boolean? = null,
)

/** Durée de conservation complète sur le téléphone, au choix dans les paramètres (null : tout garder). */
val KEEP_ON_PHONE_CHOICES: List<Int?> = listOf(7, 30, 90, null)
const val DEFAULT_KEEP_ON_PHONE_DAYS = 30

fun summaryOf(session: TrailSession): SessionSummary {
    val stats = TrackStats.of(session.segments)
    val deviation = TrailDeviation.of(session.trail, session.segments.flatten())
    return SessionSummary(stats.distanceMeters, stats.pointCount, deviation?.meanMeters, deviation?.reachedEnd)
}

/** Trace et piste retirées du téléphone (elles restent en ligne) ; notes et résumé gardés. */
fun archived(session: TrailSession): TrailSession =
    session.copy(segments = emptyList(), trail = emptyList(), archived = true, summary = summaryOf(session))

fun archived(trail: LaidTrail): LaidTrail =
    trail.copy(points = emptyList(), archived = true, lengthMeters = pathLengthMeters(trail.points))

/**
 * Ce qui peut quitter le téléphone : terminé depuis plus de [keepDays] jours, et dont le serveur a confirmé
 * avoir la version actuelle ([confirmed] : version reçue par le serveur, par clé de synchronisation).
 */
fun sessionsToArchive(sessions: List<TrailSession>, confirmed: (TrailSession) -> Long?, nowSeconds: Long, keepDays: Int): List<TrailSession> =
    sessions.filter { !it.archived && nowSeconds - it.endedAt > keepDays * 86_400L && confirmed(it) == it.version }

fun trailsToArchive(trails: List<LaidTrail>, confirmed: (LaidTrail) -> Long?, nowSeconds: Long, keepDays: Int): List<LaidTrail> =
    trails.filter { !it.archived && nowSeconds - it.endedAt > keepDays * 86_400L && confirmed(it) == it.version }
