package app.mantrailing.track

import app.mantrailing.tractive.Position
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Bilan d'une trace, pour juger si elle est exploitable (fréquence et précision des points). */
data class TrackStats(
    val pointCount: Int,
    val segmentCount: Int,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val medianIntervalSeconds: Long?,
    val maxIntervalSeconds: Long?,
    val medianUncertaintyMeters: Double?,
    val gpsShare: Double,
) {
    companion object {
        fun of(segments: List<List<Position>>): TrackStats {
            val points = segments.flatten().sortedBy { it.time }
            val intervals = points.zipWithNext { a, b -> b.time - a.time }.sorted()
            val uncertainties = points.mapNotNull { it.uncertainty }.sorted()
            val distance = segments.sumOf { seg -> seg.zipWithNext { a, b -> distanceMeters(a, b) }.sum() }
            return TrackStats(
                pointCount = points.size,
                segmentCount = segments.size,
                distanceMeters = distance,
                durationSeconds = if (points.isEmpty()) 0 else points.last().time - points.first().time,
                medianIntervalSeconds = intervals.median(),
                maxIntervalSeconds = intervals.lastOrNull(),
                medianUncertaintyMeters = uncertainties.median(),
                gpsShare = if (points.isEmpty()) 0.0 else points.count { it.sensor in GPS_SENSORS }.toDouble() / points.size,
            )
        }

        private fun <T> List<T>.median(): T? = if (isEmpty()) null else this[size / 2]
    }
}

/** Capteurs comptés comme du GPS : celui du collier Tractive et celui du téléphone. */
val GPS_SENSORS = setOf("GPS", PHONE_GPS_SENSOR)
const val PHONE_GPS_SENSOR = "PHONE_GPS"

private const val EARTH_RADIUS_M = 6_371_000.0

/**
 * Longueur d'un parcours à pied, en mètres. Une position n'est comptée qu'à [minStepMeters] de la dernière retenue :
 * le GPS qui bouge de quelques mètres pendant un arrêt n'allonge pas le parcours.
 */
fun pathLengthMeters(points: List<Position>, minStepMeters: Double = 5.0): Double {
    var anchor = points.firstOrNull() ?: return 0.0
    var length = 0.0
    for (p in points) {
        val step = distanceMeters(anchor, p)
        if (step >= minStepMeters) {
            length += step
            anchor = p
        }
    }
    // Les derniers mètres jusqu'à l'arrivée comptent même s'ils sont courts.
    if (anchor !== points.last()) length += distanceMeters(anchor, points.last())
    return length
}

/** Distance orthodromique (haversine) entre deux positions, en mètres. */
fun distanceMeters(a: Position, b: Position): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
}
