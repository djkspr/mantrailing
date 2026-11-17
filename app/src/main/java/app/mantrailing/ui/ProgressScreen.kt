package app.mantrailing.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mantrailing.session.Dog
import app.mantrailing.session.LaidTrail
import app.mantrailing.session.TrailSession
import app.mantrailing.session.sessionsOf
import app.mantrailing.track.Progress
import app.mantrailing.track.ProgressPeriod
import app.mantrailing.track.Trend
import app.mantrailing.track.TrendValue
import app.mantrailing.track.filledMonths
import app.mantrailing.track.progressOf
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DogOrange = Color(0xFFD9480F)
private val TrailBlue = Color(0xFF1C6DD0)
private val MONTH = DateTimeFormatter.ofPattern("MMM yy", Locale.FRENCH)

/** « 45 min », « 2 h 10 », « 1 j 4 h » : âge de piste lisible. */
fun formatAge(seconds: Double): String = seconds.toLong().let { s ->
    if (s >= 86_400) "${s / 86_400} j ${(s % 86_400) / 3600} h" else formatDuration(s)
}

/**
 * Progrès d'un chien : ses recherches au fil du temps (âge et longueur des pistes, écart, réussite, rythme).
 * [initialDogId] : chien affiché à l'ouverture (sinon le premier).
 */
@Composable
fun ProgressScreen(
    sessions: List<TrailSession>,
    trails: List<LaidTrail>,
    dogs: List<Dog>,
    initialDogId: String?,
    onBack: () -> Unit,
) {
    var dogId by rememberSaveable { mutableStateOf(initialDogId?.takeIf { id -> dogs.any { it.id == id } } ?: dogs.firstOrNull()?.id) }
    var period by rememberSaveable { mutableStateOf(ProgressPeriod.YEAR) }
    val dog = dogs.find { it.id == dogId }
    val progress = remember(sessions, trails, dog, period) {
        progressOf(dog?.let { sessionsOf(sessions, it) } ?: sessions, trails, period)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Retour" }) {
                Text("←", style = MaterialTheme.typography.headlineSmall)
            }
            Text(dog?.let { "Progrès de ${it.name}" } ?: "Progrès", style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (dogs.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).testTag("progress-dogs"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dogs.forEach { d -> FilterChip(selected = d.id == dogId, onClick = { dogId = d.id }, label = { Text(d.name) }) }
                }
            }
            Row(Modifier.testTag("progress-period"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressPeriod.entries.forEach { p -> FilterChip(selected = p == period, onClick = { period = p }, label = { Text(p.label) }) }
            }
            if (!progress.hasEnoughData) {
                Text(
                    if (progress.searchCount == 0) "Pas encore de recherche sur cette période."
                    else "Une seule recherche sur cette période : les progrès s'affichent à partir de deux.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("progress-empty"),
                )
                return@Column
            }
            SummaryTiles(progress)
            Records(progress)
            val dated = progress.points
            LineChart(
                "Âge de la piste au départ", "Plus haut : pistes plus vieilles, donc plus difficiles.",
                dated.mapNotNull { p -> p.trailAgeSeconds?.let { p.startedAt to it.toDouble() } }, TrailBlue,
                format = ::formatAge, tag = "chart-age",
            )
            LineChart(
                "Écart moyen à la piste", "Plus bas : le chien colle mieux à la piste.",
                dated.mapNotNull { p -> p.meanDeviationMeters?.let { p.startedAt to it } }, DogOrange,
                format = { "%.0f m".format(Locale.FRENCH, it) }, tag = "chart-deviation",
            )
            LineChart(
                "Longueur de la piste", null,
                dated.mapNotNull { p -> p.trailLengthMeters?.let { p.startedAt to it } }, TrailBlue,
                format = { formatDistance(it) }, tag = "chart-length",
            )
            MonthlyBars(progress)
        }
    }
}

@Composable
private fun SummaryTiles(progress: Progress) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Recherches", "${progress.searchCount}", null, Modifier.weight(1f), "tile-count")
            Tile(
                "Trouvé", progress.successRate?.let { "%.0f %%".format(Locale.FRENCH, it * 100) } ?: "–",
                progress.successTrend?.let { arrow(it.trend(0.05), higherIsBetter = true) }, Modifier.weight(1f), "tile-success",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(
                "Âge de piste moyen", progress.meanTrailAgeSeconds?.let(::formatAge) ?: "–",
                progress.trailAgeTrend?.let { arrow(it.trend(60.0), higherIsBetter = true) }, Modifier.weight(1f), "tile-age",
            )
            Tile(
                "Écart moyen", progress.meanDeviationMeters?.let { "%.0f m".format(Locale.FRENCH, it) } ?: "–",
                progress.deviationTrend?.let { arrow(it.trend(1.0), higherIsBetter = false) }, Modifier.weight(1f), "tile-deviation",
            )
        }
        Text(
            "Flèches : les ${Progress.TREND_WINDOW} dernières recherches comparées aux précédentes (vert : en progrès).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Flèche de tendance et sa couleur : vert si c'est un progrès, rouge sinon. */
private data class Arrow(val symbol: String, val good: Boolean?, val label: String)

private fun arrow(trend: Trend, higherIsBetter: Boolean): Arrow = when (trend) {
    Trend.STABLE -> Arrow("→", null, "stable")
    Trend.UP -> Arrow("↗", higherIsBetter, "en hausse")
    Trend.DOWN -> Arrow("↘", !higherIsBetter, "en baisse")
}

@Composable
private fun Tile(label: String, value: String, arrow: Arrow?, modifier: Modifier, tag: String) {
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = modifier.testTag(tag)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(value, style = MaterialTheme.typography.titleLarge)
                arrow?.let {
                    val color = when (it.good) {
                        true -> Color(0xFF2B8A3E)
                        false -> Color(0xFFC92A2A)
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(it.symbol, color = color, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = it.label })
                }
            }
        }
    }
}

@Composable
private fun Records(progress: Progress) {
    val oldest = progress.oldestFound
    val longest = progress.longestFound
    if (oldest == null && longest == null) return
    Column(Modifier.testTag("records"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Records", style = MaterialTheme.typography.titleMedium)
        oldest?.let { Text("Piste la plus vieille trouvée : ${formatAge(it.trailAgeSeconds!!.toDouble())} (${formatSessionDate(it.startedAt)})", style = MaterialTheme.typography.bodyMedium) }
        longest?.let { Text("Piste la plus longue trouvée : ${formatDistance(it.trailLengthMeters!!)} (${formatSessionDate(it.startedAt)})", style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Courbe d'une valeur au fil des recherches (une recherche = un point, espacées régulièrement). */
@Composable
private fun LineChart(title: String, hint: String?, values: List<Pair<Long, Double>>, color: Color, format: (Double) -> String, tag: String) {
    if (values.size < 2) return
    val min = values.minOf { it.second }
    val max = values.maxOf { it.second }
    val description = "$title : de ${format(values.first().second)} à ${format(values.last().second)}, " +
        "minimum ${format(min)}, maximum ${format(max)}, sur ${values.size} recherches."
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(format(max), style = MaterialTheme.typography.labelSmall)
            Text("${formatSessionDate(values.first().first)} → ${formatSessionDate(values.last().first)}", style = MaterialTheme.typography.labelSmall)
        }
        val grid = MaterialTheme.colorScheme.outlineVariant
        Canvas(Modifier.fillMaxWidth().height(140.dp).testTag(tag).semantics { contentDescription = description }) {
            val pad = 8.dp.toPx()
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad
            val span = (max - min).takeIf { it > 0 } ?: 1.0
            fun at(i: Int, v: Double) = Offset(pad + w * i / (values.size - 1), pad + h * (1 - ((v - min) / span)).toFloat())
            listOf(0f, 0.5f, 1f).forEach { f -> drawLine(grid, Offset(pad, pad + h * f), Offset(pad + w, pad + h * f), 1.dp.toPx()) }
            val path = Path()
            values.forEachIndexed { i, (_, v) -> at(i, v).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
            values.forEachIndexed { i, (_, v) -> drawCircle(color, 4.dp.toPx(), at(i, v)) }
        }
        Text(format(min), style = MaterialTheme.typography.labelSmall)
    }
}

/** Recherches par mois, en barres. */
@Composable
private fun MonthlyBars(progress: Progress) {
    val months = filledMonths(progress.monthly)
    if (months.size < 2) return
    val max = months.maxOf { it.count }.coerceAtLeast(1)
    val description = "Recherches par mois : " + months.joinToString { "${it.month.format(MONTH)} ${it.count}" } + "."
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Recherches par mois", style = MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().height(110.dp).testTag("chart-months").semantics { contentDescription = description }) {
            val gap = 4.dp.toPx()
            val barWidth = (size.width - gap * (months.size - 1)) / months.size
            months.forEachIndexed { i, m ->
                val barHeight = size.height * m.count / max
                drawRect(DogOrange, Offset(i * (barWidth + gap), size.height - barHeight), androidx.compose.ui.geometry.Size(barWidth, barHeight))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(months.first().month.format(MONTH), style = MaterialTheme.typography.labelSmall)
            Text("max $max", style = MaterialTheme.typography.labelSmall)
            Text(months.last().month.format(MONTH), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Utilisé par les tests : la tendance affichée pour une paire de moyennes. */
internal fun trendLabel(value: TrendValue, higherIsBetter: Boolean, minDelta: Double = 0.0): String =
    arrow(value.trend(minDelta), higherIsBetter).let { "${it.symbol} ${it.label}" }

