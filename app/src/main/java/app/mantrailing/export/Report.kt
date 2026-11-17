package app.mantrailing.export

import app.mantrailing.session.LaidTrail
import app.mantrailing.session.NoteCategory
import app.mantrailing.session.SearchNote
import app.mantrailing.session.TrailSession
import app.mantrailing.session.TrainingLog
import app.mantrailing.session.stars
import app.mantrailing.track.TrackStats
import app.mantrailing.track.TrailDeviation
import app.mantrailing.track.pathLengthMeters
import app.mantrailing.ui.formatDistance
import app.mantrailing.ui.formatDuration
import app.mantrailing.ui.sourceLabel
import app.mantrailing.ui.trailLength
import app.mantrailing.weather.Weather
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ligne « libellé : valeur » d'une rubrique du rapport. */
data class ReportRow(val label: String, val value: String)

data class ReportSection(val title: String, val rows: List<ReportRow>)

/** Note listée dans le rapport : moment depuis le départ, famille, texte. */
data class ReportNote(val id: String, val offset: String, val category: String, val text: String, val warning: Boolean = false)

/**
 * Contenu d'un rapport PDF, indépendant du dessin : une recherche (piste travaillée) ou une piste posée.
 * [notes] : notes du maître-chien ; [trailerNotes] : signalements du traceur pendant la pose.
 */
data class Report(
    val title: String,
    val subtitle: String,
    val sections: List<ReportSection>,
    val notes: List<ReportNote>,
    val trailerNotes: List<ReportNote>,
    val fileName: String,
)

private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
private val HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)
private val FILE_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH'h'mm", Locale.FRENCH)

private fun at(epochSeconds: Long, zone: ZoneId) = Instant.ofEpochSecond(epochSeconds).atZone(zone)

private fun period(start: Long, end: Long, zone: ZoneId) =
    "${at(start, zone).format(DAY)}, de ${at(start, zone).format(HOUR)} à ${at(end, zone).format(HOUR)}"

private fun percent(share: Double) = "%.0f %%".format(Locale.FRENCH, share * 100)

private fun meters(value: Double) = "%.0f m".format(Locale.FRENCH, value)

/** Nom de fichier sans caractère gênant : « Mantrailing_recherche_Rex_2026-11-17_09h30.pdf ». */
private fun fileName(kind: String, who: String?, start: Long, zone: ZoneId): String {
    val clean = who?.let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
        ?.replace(Regex("\\p{M}"), "")?.replace(Regex("[^A-Za-z0-9-]+"), "-")?.trim('-')?.takeIf { it.isNotEmpty() }
    return listOfNotNull("Mantrailing", kind, clean, at(start, zone).format(FILE_DAY)).joinToString("_") + ".pdf"
}

/** Rubrique « Carnet » : champs remplis du carnet d'entraînement (null si vide). */
internal fun logSection(log: TrainingLog?): ReportSection? {
    val l = log ?: return null
    val rows = listOfNotNull(
        l.runner?.let { ReportRow("Traceur", it) },
        l.scentArticle?.let { ReportRow("Objet odorant", it) },
        l.place?.let { ReportRow("Lieu", it) },
        l.terrain.takeIf { it.isNotEmpty() }?.let { t -> ReportRow("Terrain", t.joinToString { it.label }) },
        l.difficulties.takeIf { it.isNotEmpty() }?.let { d -> ReportRow("Difficultés", d.joinToString { it.label }) },
        l.reward?.let { ReportRow("Récompense", it) },
        l.rating?.let { ReportRow("Travail de l'équipe", "${stars(it)} ($it/5)") },
        l.comment?.let { ReportRow("Commentaire", it) },
    )
    return ReportSection("Carnet", rows).takeIf { rows.isNotEmpty() }
}

private fun weatherRows(label: String, weather: Weather?, zone: ZoneId): List<ReportRow> =
    weather?.let { listOf(ReportRow(label, "${it.summary} (${at(it.time, zone).format(HOUR)})")) }.orEmpty()

/** Heure de chaque note, et temps écoulé depuis [start] quand il est connu (« 09:42 · +3 min 20s »). */
private fun reportNotes(notes: List<SearchNote>, start: Long?, zone: ZoneId): List<ReportNote> =
    notes.sortedBy { it.time }.map { note ->
        ReportNote(
            id = note.id,
            offset = at(note.time, zone).format(HOUR) + (start?.let { " · +" + formatDuration((note.time - it).coerceAtLeast(0)) } ?: ""),
            category = note.type.category.label,
            text = note.detail + if (note.lat == null) " (sans position)" else "",
            warning = note.type.isWarning,
        )
    }

/** Rapport d'une recherche : le chien, la piste suivie, l'écart entre les deux, la météo et toutes les notes. */
fun sessionReport(session: TrailSession, spaceName: String, zone: ZoneId = ZoneId.systemDefault()): Report {
    val stats = TrackStats.of(session.segments)
    val deviation = TrailDeviation.of(session.trail, session.segments.flatten())
    val outcome = session.notes.lastOrNull { it.type.category == NoteCategory.END }

    val general = buildList {
        add(ReportRow("Chien", session.dogName))
        add(ReportRow("Date", period(session.startedAt, session.endedAt, zone)))
        add(ReportRow("Durée", formatDuration(session.endedAt - session.startedAt)))
        add(ReportRow("Trace prise par", sourceLabel(session.source)))
        add(ReportRow("Espace", spaceName))
        outcome?.let { add(ReportRow("Issue", it.detail)) }
    }
    val trail = buildList {
        if (session.trail.isEmpty()) {
            add(ReportRow("Piste", "Recherche sans piste enregistrée"))
        } else {
            add(ReportRow("Longueur de la piste", formatDistance(pathLengthMeters(session.trail))))
            session.trailAgeSeconds?.let { add(ReportRow("Âge au départ du chien", formatDuration(it))) }
        }
    }
    val track = buildList {
        val distance = if (stats.pointCount > 0) stats.distanceMeters else session.summary?.distanceMeters
        distance?.let { add(ReportRow("Distance parcourue par le chien", formatDistance(it))) }
        if (stats.pointCount > 0) {
            add(ReportRow("Points enregistrés", "${stats.pointCount}"))
            stats.medianIntervalSeconds?.let { add(ReportRow("Un point toutes les", formatDuration(it))) }
            stats.medianUncertaintyMeters?.let { add(ReportRow("Précision", "± ${meters(it)}")) }
            add(ReportRow("Positions GPS", percent(stats.gpsShare)))
        }
    }
    val comparison = deviation?.let {
        listOf(
            ReportRow("Écart moyen à la piste", meters(it.meanMeters)),
            ReportRow("Écart maximal", meters(it.maxMeters)),
            ReportRow("Temps passé sur la piste", percent(it.onTrailShare)),
            ReportRow("Piste couverte", percent(it.coverage)),
            ReportRow("Arrivée", if (it.reachedEnd) "atteinte" else "pas atteinte"),
        )
    }
    val weather = weatherRows("Au départ du chien", session.weather, zone) + weatherRows("Pendant la pose", session.trailWeather, zone)

    return Report(
        title = "Recherche de ${session.dogName}",
        subtitle = "${at(session.startedAt, zone).format(DAY)} à ${at(session.startedAt, zone).format(HOUR)}",
        sections = listOfNotNull(
            ReportSection("Séance", general),
            ReportSection("Piste suivie", trail),
            ReportSection("Trace du chien", track).takeIf { track.isNotEmpty() },
            comparison?.let { ReportSection("Comparaison avec la piste", it) },
            ReportSection("Météo", weather).takeIf { weather.isNotEmpty() },
            logSection(session.log),
        ),
        notes = reportNotes(session.notes, session.startedAt, zone),
        // Signalements faits pendant la pose, dont le début n'est pas gardé avec la recherche : heure seule.
        trailerNotes = reportNotes(session.trailNotes, null, zone),
        fileName = fileName("recherche", session.dogName, session.startedAt, zone),
    )
}

/** Rapport d'une piste posée (travaillée ou pas encore) : parcours, météo, signalements du traceur. */
fun trailReport(trail: LaidTrail, spaceName: String, zone: ZoneId = ZoneId.systemDefault()): Report {
    val general = listOf(
        ReportRow("Date", period(trail.startedAt, trail.endedAt, zone)),
        ReportRow("Durée de la pose", formatDuration(trail.endedAt - trail.startedAt)),
        ReportRow("Longueur", formatDistance(trailLength(trail))),
        ReportRow("Points enregistrés", "${trail.points.size}"),
        ReportRow("Espace", spaceName),
    )
    val weather = weatherRows("Pendant la pose", trail.weather, zone)
    return Report(
        title = "Piste posée",
        subtitle = "${at(trail.startedAt, zone).format(DAY)} à ${at(trail.startedAt, zone).format(HOUR)}",
        sections = listOfNotNull(
            ReportSection("Pose", general),
            ReportSection("Météo", weather).takeIf { weather.isNotEmpty() },
            logSection(trail.log),
        ),
        notes = emptyList(),
        trailerNotes = reportNotes(trail.notes, trail.startedAt, zone),
        fileName = fileName("piste", null, trail.startedAt, zone),
    )
}
