package app.mantrailing.track

import app.mantrailing.tractive.Position
import kotlin.math.hypot

/** Largeurs de couloir proposées en mode formateur (mètres de part et d'autre de la piste). */
val CORRIDOR_CHOICES = listOf(10, 20, 30, 50)
const val DEFAULT_CORRIDOR_METERS = 20

/** Ce qui vient de changer, pour prévenir le formateur (vibration, message). */
enum class CorridorEvent { LEFT, BACK, NEAR_END }

/**
 * État du couloir après la dernière position.
 * @property distanceMeters distance de l'équipe à la piste
 * @property outside l'équipe est hors du couloir (confirmé sur plusieurs positions)
 * @property exits nombre de sorties du couloir depuis le départ
 * @property nearEnd l'arrivée de la piste est proche
 * @property event changement à signaler, sur cette position seulement
 */
data class CorridorStatus(
    val distanceMeters: Double,
    val outside: Boolean,
    val exits: Int,
    val nearEnd: Boolean,
    val event: CorridorEvent? = null,
)

/**
 * Couloir virtuel autour de la piste (mode formateur). Le formateur suit l'équipe avec son téléphone : sa position
 * est celle de l'équipe. Pour ne pas alerter à chaque saut du GPS, la sortie n'est confirmée qu'après
 * [exitFixes] positions de suite hors du couloir, et le retour qu'à [hysteresisMeters] à l'intérieur.
 */
class Corridor(
    trail: List<Position>,
    val widthMeters: Double,
    private val exitFixes: Int = 3,
    private val backFixes: Int = 2,
    private val hysteresisMeters: Double = 5.0,
    private val endMeters: Double = 25.0,
) {
    private val projection = LocalProjection(trail.first())
    private val line = trail.map(projection::xy)
    private val end = line.last()
    // L'arrivée n'est annoncée qu'après la première moitié du parcours (piste en boucle : départ = arrivée).
    private val trailLength = line.zipWithNext { a, b -> hypot(b.first - a.first, b.second - a.second) }.sum()

    private var outsideCount = 0
    private var insideCount = 0
    private var outside = false
    private var exits = 0
    private var nearEndAnnounced = false
    private var walked = 0.0
    private var last: Pair<Double, Double>? = null

    init {
        require(trail.size >= 2) { "Il faut une piste d'au moins deux points." }
    }

    fun distanceTo(p: Position): Double = distanceToPolyline(projection.xy(p), line)

    fun update(p: Position): CorridorStatus {
        val xy = projection.xy(p)
        last?.let { walked += hypot(xy.first - it.first, xy.second - it.second) }
        last = xy
        val d = distanceToPolyline(xy, line)
        var event: CorridorEvent? = null
        if (!outside) {
            outsideCount = if (d > widthMeters) outsideCount + 1 else 0
            if (outsideCount >= exitFixes) {
                outside = true
                exits++
                insideCount = 0
                event = CorridorEvent.LEFT
            }
        } else {
            insideCount = if (d <= widthMeters - hysteresisMeters) insideCount + 1 else 0
            if (insideCount >= backFixes) {
                outside = false
                outsideCount = 0
                event = CorridorEvent.BACK
            }
        }
        val nearEnd = hypot(xy.first - end.first, xy.second - end.second) <= endMeters && walked >= trailLength / 2
        if (nearEnd && !nearEndAnnounced && event == null) {
            nearEndAnnounced = true
            event = CorridorEvent.NEAR_END
        }
        return CorridorStatus(d, outside, exits, nearEnd, event)
    }
}

internal fun distanceToPolyline(p: Pair<Double, Double>, line: List<Pair<Double, Double>>): Double =
    line.zipWithNext { a, b -> distanceToSegment(p, a, b) }.min()

private fun distanceToSegment(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val dx = b.first - a.first
    val dy = b.second - a.second
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((p.first - a.first) * dx + (p.second - a.second) * dy) / len2).coerceIn(0.0, 1.0)
    return hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
}
