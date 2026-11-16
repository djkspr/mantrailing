package app.mantrailing.track

import app.mantrailing.tractive.Position
import kotlin.math.cos
import kotlin.math.hypot

/** Distance (m) en dessous de laquelle le chien est considéré sur la piste. */
const val ON_TRAIL_METERS = 15.0

/**
 * Comparaison de la trace du chien avec la piste posée par le traceur.
 * @property meanMeters écart moyen du chien à la piste
 * @property maxMeters plus grand écart
 * @property onTrailShare part des positions du chien à moins de [ON_TRAIL_METERS] de la piste
 * @property coverage part de la piste passée à moins de [ON_TRAIL_METERS] par le chien
 * @property reachedEnd le chien est passé à moins de [ON_TRAIL_METERS] de l'arrivée de la piste
 */
data class TrailDeviation(
    val meanMeters: Double,
    val maxMeters: Double,
    val onTrailShare: Double,
    val coverage: Double,
    val reachedEnd: Boolean,
) {
    companion object {
        fun of(trail: List<Position>, dog: List<Position>): TrailDeviation? {
            if (trail.size < 2 || dog.isEmpty()) return null
            val projection = LocalProjection(trail.first())
            val trailXY = trail.map(projection::xy)
            val dogXY = dog.map(projection::xy)
            val gaps = dogXY.map { distanceToPolyline(it, trailXY) }
            val covered = trailXY.count { t -> dogXY.any { d -> hypot(d.first - t.first, d.second - t.second) <= ON_TRAIL_METERS } }
            val end = trailXY.last()
            return TrailDeviation(
                meanMeters = gaps.average(),
                maxMeters = gaps.max(),
                onTrailShare = gaps.count { it <= ON_TRAIL_METERS }.toDouble() / gaps.size,
                coverage = covered.toDouble() / trailXY.size,
                reachedEnd = dogXY.any { hypot(it.first - end.first, it.second - end.second) <= ON_TRAIL_METERS },
            )
        }

        private fun distanceToPolyline(p: Pair<Double, Double>, line: List<Pair<Double, Double>>): Double =
            line.zipWithNext { a, b -> distanceToSegment(p, a, b) }.min()

        private fun distanceToSegment(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val dx = b.first - a.first
            val dy = b.second - a.second
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((p.first - a.first) * dx + (p.second - a.second) * dy) / len2).coerceIn(0.0, 1.0)
            return hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
        }
    }
}

/** Projection plane locale (équirectangulaire), précise à quelques centimètres sur l'étendue d'une piste. */
private class LocalProjection(origin: Position) {
    private val lat0 = origin.lat
    private val lon0 = origin.lon
    private val mPerDegLat = Math.toRadians(1.0) * 6_371_000.0
    private val mPerDegLon = mPerDegLat * cos(Math.toRadians(origin.lat))

    fun xy(p: Position) = (p.lon - lon0) * mPerDegLon to (p.lat - lat0) * mPerDegLat
}
