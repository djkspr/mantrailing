package app.mantrailing.track

import app.mantrailing.session.LaidTrail
import app.mantrailing.session.NoteCategory
import app.mantrailing.session.NoteType
import app.mantrailing.session.TrailSession
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Issue d'une recherche, notée à la fin (null : terminée sans préciser). */
enum class Outcome { FOUND, ABANDON, WRONG_ID }

/**
 * Une recherche, résumée pour suivre les progrès du chien. Les valeurs absentes (null) ne sont pas connues :
 * recherche sans piste, trace plus sur le téléphone sans résumé, etc.
 */
data class SearchPoint(
    val id: String,
    val startedAt: Long,
    val trailAgeSeconds: Long?,
    val trailLengthMeters: Double?,
    val dogDistanceMeters: Double?,
    val meanDeviationMeters: Double?,
    val reachedEnd: Boolean?,
    val outcome: Outcome?,
)

/** Période affichée : les 3 ou 12 derniers mois, ou tout. */
enum class ProgressPeriod(val label: String, val days: Long?) {
    THREE_MONTHS("3 mois", 92),
    YEAR("12 mois", 366),
    ALL("Tout", null),
}

/** Évolution d'une valeur : récentes comparées aux précédentes. */
enum class Trend { UP, DOWN, STABLE }

/** Moyenne de [recent] comparée à [previous] ; null s'il manque des valeurs d'un côté. */
data class TrendValue(val recent: Double, val previous: Double) {
    /** Écart de plus de 10 % (et d'au moins [minDelta]) : en hausse ou en baisse, sinon stable. */
    fun trend(minDelta: Double = 0.0): Trend {
        val delta = recent - previous
        val threshold = maxOf(kotlin.math.abs(previous) * 0.1, minDelta)
        return when {
            delta > threshold -> Trend.UP
            delta < -threshold -> Trend.DOWN
            else -> Trend.STABLE
        }
    }
}

/** Nombre de recherches d'un mois. */
data class MonthCount(val month: YearMonth, val count: Int)

/** Bilan des recherches d'un chien sur une période. */
data class Progress(
    /** Recherches de la période, de la plus ancienne à la plus récente. */
    val points: List<SearchPoint>,
    val searchCount: Int,
    /** Part des recherches terminées par « Trouvé », parmi celles dont l'issue est notée. */
    val successRate: Double?,
    val meanTrailAgeSeconds: Double?,
    val meanDeviationMeters: Double?,
    val meanTrailLengthMeters: Double?,
    /** Âge de piste : [TREND_WINDOW] dernières recherches comparées aux précédentes. */
    val trailAgeTrend: TrendValue?,
    val deviationTrend: TrendValue?,
    val successTrend: TrendValue?,
    /** Piste la plus vieille trouvée, piste la plus longue trouvée. */
    val oldestFound: SearchPoint?,
    val longestFound: SearchPoint?,
    val monthly: List<MonthCount>,
    // Extension : une série « note moyenne » (carnet d'entraînement) pourra s'ajouter ici, calculée comme les autres.
) {
    val hasEnoughData: Boolean get() = searchCount >= 2

    companion object {
        /** Nombre de recherches récentes comparées aux précédentes pour les tendances. */
        const val TREND_WINDOW = 5
    }
}

fun outcomeOf(session: TrailSession): Outcome? = when (session.notes.lastOrNull { it.type.category == NoteCategory.END }?.type) {
    NoteType.FOUND -> Outcome.FOUND
    NoteType.ABANDON -> Outcome.ABANDON
    NoteType.WRONG_ID -> Outcome.WRONG_ID
    else -> null
}

/**
 * Résumé d'une recherche. Une recherche qui n'est plus qu'en ligne n'a plus sa trace ni sa piste : on prend son
 * résumé, et la longueur de la piste posée qu'elle suivait ([trails]) si le téléphone la connaît.
 */
fun searchPoint(session: TrailSession, trails: List<LaidTrail> = emptyList()): SearchPoint {
    val hasTrack = session.segments.any { it.isNotEmpty() }
    val deviation = if (hasTrack && session.trail.isNotEmpty()) TrailDeviation.of(session.trail, session.segments.flatten()) else null
    val trailLength = when {
        session.trail.size >= 2 -> pathLengthMeters(session.trail)
        else -> session.trailId?.let { id -> trails.find { it.id == id } }?.let { t ->
            t.lengthMeters ?: t.points.takeIf { it.size >= 2 }?.let(::pathLengthMeters)
        }
    }
    return SearchPoint(
        id = session.id,
        startedAt = session.startedAt,
        trailAgeSeconds = session.trailAgeSeconds,
        trailLengthMeters = trailLength,
        dogDistanceMeters = if (hasTrack) TrackStats.of(session.segments).distanceMeters else session.summary?.distanceMeters,
        meanDeviationMeters = deviation?.meanMeters ?: session.summary?.meanDeviationMeters,
        reachedEnd = deviation?.reachedEnd ?: session.summary?.reachedEnd,
        outcome = outcomeOf(session),
    )
}

/** Bilan des recherches [sessions] (celles d'un chien) sur [period], à la date [now]. */
fun progressOf(
    sessions: List<TrailSession>,
    trails: List<LaidTrail> = emptyList(),
    period: ProgressPeriod = ProgressPeriod.ALL,
    now: Long = Instant.now().epochSecond,
    zone: ZoneId = ZoneId.systemDefault(),
): Progress {
    val from = period.days?.let { now - it * 86_400 }
    val points = sessions
        .filter { from == null || it.startedAt >= from }
        .sortedBy { it.startedAt }
        .map { searchPoint(it, trails) }

    fun List<Double>.meanOrNull() = if (isEmpty()) null else average()
    fun success(list: List<SearchPoint>) = list.mapNotNull { it.outcome }.let { o -> if (o.isEmpty()) null else o.count { it == Outcome.FOUND }.toDouble() / o.size }
    fun trend(values: (SearchPoint) -> Double?): TrendValue? {
        val known = points.mapNotNull(values)
        if (known.size < 2) return null
        val recent = known.takeLast(minOf(Progress.TREND_WINDOW, known.size / 2 + known.size % 2))
        val previous = known.dropLast(recent.size).takeLast(Progress.TREND_WINDOW)
        if (previous.isEmpty()) return null
        return TrendValue(recent.average(), previous.average())
    }
    val successTrend = run {
        val withOutcome = points.filter { it.outcome != null }
        if (withOutcome.size < 2) return@run null
        val recent = withOutcome.takeLast(minOf(Progress.TREND_WINDOW, withOutcome.size / 2 + withOutcome.size % 2))
        val previous = withOutcome.dropLast(recent.size).takeLast(Progress.TREND_WINDOW)
        if (previous.isEmpty()) null else TrendValue(success(recent)!!, success(previous)!!)
    }
    val found = points.filter { it.outcome == Outcome.FOUND }
    val monthly = points.groupBy { YearMonth.from(Instant.ofEpochSecond(it.startedAt).atZone(zone)) }
        .map { (month, list) -> MonthCount(month, list.size) }
        .sortedBy { it.month }

    return Progress(
        points = points,
        searchCount = points.size,
        successRate = success(points),
        meanTrailAgeSeconds = points.mapNotNull { it.trailAgeSeconds?.toDouble() }.meanOrNull(),
        meanDeviationMeters = points.mapNotNull { it.meanDeviationMeters }.meanOrNull(),
        meanTrailLengthMeters = points.mapNotNull { it.trailLengthMeters }.meanOrNull(),
        trailAgeTrend = trend { it.trailAgeSeconds?.toDouble() },
        deviationTrend = trend { it.meanDeviationMeters },
        successTrend = successTrend,
        oldestFound = found.filter { it.trailAgeSeconds != null }.maxByOrNull { it.trailAgeSeconds!! },
        longestFound = found.filter { it.trailLengthMeters != null }.maxByOrNull { it.trailLengthMeters!! },
        monthly = monthly,
    )
}

/** Mois de [first] à [last] inclus, avec 0 pour les mois sans recherche (barres régulières). */
fun filledMonths(monthly: List<MonthCount>): List<MonthCount> {
    if (monthly.isEmpty()) return emptyList()
    val counts = monthly.associate { it.month to it.count }
    val result = mutableListOf<MonthCount>()
    var m = monthly.first().month
    val last = monthly.last().month
    while (!m.isAfter(last)) {
        result += MonthCount(m, counts[m] ?: 0)
        m = m.plusMonths(1)
    }
    return result
}
