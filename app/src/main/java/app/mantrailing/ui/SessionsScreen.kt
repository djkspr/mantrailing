package app.mantrailing.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mantrailing.session.Dog
import app.mantrailing.session.LaidTrail
import app.mantrailing.session.TrailSession
import app.mantrailing.session.sessionsOf
import app.mantrailing.sync.Team
import app.mantrailing.track.TrackStats
import app.mantrailing.track.TrailDeviation
import java.util.Locale

data class SessionsActions(
    val onBack: () -> Unit = {},
    val onOpen: (TrailSession) -> Unit = {},
    val onDelete: (TrailSession) -> Unit = {},
    val onOpenTrail: (LaidTrail) -> Unit = {},
    val onDeleteTrail: (LaidTrail) -> Unit = {},
    /** Partage avec une équipe, ou retour en perso (null). */
    val onMoveSession: (TrailSession, String?) -> Unit = { _, _ -> },
    val onMoveTrail: (LaidTrail, String?) -> Unit = { _, _ -> },
)

/** Élément à supprimer, en attente de confirmation. */
private data class PendingDelete(val label: String, val confirm: () -> Unit)

/** Élément à partager : son espace actuel et l'action qui le déplace. */
private data class PendingMove(val title: String, val current: String?, val move: (String?) -> Unit)

/** Filtre d'espace : tout, perso, ou une équipe. */
private const val ALL_SPACES = "*"
private const val PERSO = "perso"

/**
 * « Mes pistes » : les pistes posées par le traceur et les recherches du chien, les plus récentes d'abord,
 * de l'espace perso et des équipes.
 */
@Composable
fun SessionsScreen(
    allSessions: List<TrailSession>,
    allTrails: List<LaidTrail>,
    dogs: List<Dog>,
    actions: SessionsActions,
    teams: List<Team> = emptyList(),
) {
    var toDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var toMove by remember { mutableStateOf<PendingMove?>(null) }
    val searched = remember(allSessions) { allSessions.mapNotNull { it.trailId }.toSet() }
    // Filtre des recherches par chien (null : tous). Les pistes posées servent à tous les chiens.
    var dogFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var spaceFilter by rememberSaveable { mutableStateOf(ALL_SPACES) }
    val filterDog = dogs.find { it.id == dogFilter }
    fun inSpace(spaceId: String?) = spaceFilter == ALL_SPACES || (spaceId ?: PERSO) == spaceFilter
    val sessions = remember(allSessions, filterDog, spaceFilter) {
        (filterDog?.let { sessionsOf(allSessions, it) } ?: allSessions).filter { inSpace(it.spaceId) }
    }
    val trails = remember(allTrails, spaceFilter) { allTrails.filter { inSpace(it.spaceId) } }
    fun spaceName(spaceId: String?) = spaceId?.let { id -> teams.find { it.id == id }?.name ?: "Équipe" } ?: "Perso"

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.onBack, modifier = Modifier.semantics { contentDescription = "Retour" }) {
                Text("←", style = MaterialTheme.typography.headlineSmall)
            }
            Text("Mes pistes", style = MaterialTheme.typography.headlineSmall)
        }
        if (teams.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).testTag("space-filter"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = spaceFilter == ALL_SPACES, onClick = { spaceFilter = ALL_SPACES }, label = { Text("Tout") })
                FilterChip(selected = spaceFilter == PERSO, onClick = { spaceFilter = PERSO }, label = { Text("Perso") })
                teams.forEach { team ->
                    FilterChip(selected = spaceFilter == team.id, onClick = { spaceFilter = team.id }, label = { Text(team.name) })
                }
            }
        }
        if (dogs.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).testTag("dog-filter"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filterDog == null, onClick = { dogFilter = null }, label = { Text("Tous les chiens") })
                dogs.forEach { dog ->
                    FilterChip(selected = filterDog?.id == dog.id, onClick = { dogFilter = dog.id }, label = { Text(dog.name) })
                }
            }
        }
        if (filterDog != null && sessions.isEmpty()) {
            Text("Pas encore de recherche pour ${filterDog.name}.", style = MaterialTheme.typography.bodyMedium)
        }
        if (allSessions.isEmpty() && allTrails.isEmpty()) {
            Text(
                "Rien d'enregistré pour l'instant. Sur l'accueil, le traceur commence par « Poser une piste » ; " +
                    "au départ du chien, « Démarrer une recherche ».",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (trails.isNotEmpty()) {
                item { SectionTitle("Pistes posées") }
                items(trails, key = { "trail-${it.id}" }) { trail ->
                    ItemCard(
                        tag = "trail-${trail.id}",
                        title = formatSessionDate(trail.startedAt).replaceFirstChar { it.uppercase() },
                        line1 = (if (trail.id in searched) "Piste travaillée" else "Pas encore travaillée") +
                            spaceSuffix(teams, spaceName(trail.spaceId)),
                        line2 = "%.2f km · pose en %s".format(Locale.FRENCH, trailLength(trail) / 1000, formatDuration(trail.endedAt - trail.startedAt)) +
                            onlineSuffix(trail.archived),
                        onClick = { actions.onOpenTrail(trail) },
                        onShare = if (teams.isEmpty()) null else {
                            { toMove = PendingMove("Piste posée : partager avec…", trail.spaceId) { actions.onMoveTrail(trail, it) } }
                        },
                        onDelete = { toDelete = PendingDelete("la piste posée le ${formatSessionDate(trail.startedAt)}") { actions.onDeleteTrail(trail) } },
                    )
                }
            }
            if (sessions.isNotEmpty()) {
                item { SectionTitle("Recherches") }
                items(sessions, key = { "session-${it.id}" }) { session ->
                    ItemCard(
                        tag = "session-${session.id}",
                        title = formatSessionDate(session.startedAt).replaceFirstChar { it.uppercase() },
                        line1 = "${session.dogName} · ${sourceLabel(session.source)}" +
                            (session.trailAgeSeconds?.let { " · piste de ${formatDuration(it)}" } ?: "") +
                            (if (session.corridorMeters != null) " · mode formateur" else "") +
                            spaceSuffix(teams, spaceName(session.spaceId)),
                        line2 = remember(session) { sessionLine(session) } + onlineSuffix(session.archived),
                        onClick = { actions.onOpen(session) },
                        onShare = if (teams.isEmpty()) null else {
                            { toMove = PendingMove("Recherche : partager avec…", session.spaceId) { actions.onMoveSession(session, it) } }
                        },
                        onDelete = { toDelete = PendingDelete("la recherche du ${formatSessionDate(session.startedAt)}") { actions.onDelete(session) } },
                    )
                }
            }
        }
    }

    toDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer ?") },
            text = {
                Text(
                    "${pending.label.replaceFirstChar { it.uppercase() }} sera supprimée de ce téléphone" +
                        if (teams.isNotEmpty()) ", de la sauvegarde en ligne et pour l'équipe qui la voit." else ".",
                )
            },
            confirmButton = {
                TextButton(onClick = { pending.confirm(); toDelete = null }) { Text("Supprimer définitivement") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
    toMove?.let { pending ->
        SpaceChoiceDialog(
            title = pending.title,
            teams = teams,
            selected = pending.current,
            onChoose = { pending.move(it); toMove = null },
            onDismiss = { toMove = null },
        )
    }
}

/** Bilan d'une recherche ; celle qui n'est plus qu'en ligne garde son résumé. */
fun sessionLine(session: TrailSession): String {
    val duration = formatDuration(session.endedAt - session.startedAt)
    val summary = session.summary
    if (session.archived && summary != null) {
        return if (summary.meanDeviationMeters != null) {
            "%.2f km · %s · écart moyen %.0f m · arrivée %s".format(
                Locale.FRENCH, summary.distanceMeters / 1000, duration, summary.meanDeviationMeters,
                if (summary.reachedEnd == true) "atteinte" else "pas atteinte",
            )
        } else {
            "%.2f km · %s".format(Locale.FRENCH, summary.distanceMeters / 1000, duration)
        }
    }
    val stats = TrackStats.of(session.segments)
    val deviation = TrailDeviation.of(session.trail, session.segments.flatten())
    return when {
        stats.pointCount == 0 -> "Trace pas encore récupérée · $duration"
        deviation != null -> "%.2f km · %s · écart moyen %.0f m · arrivée %s".format(
            Locale.FRENCH, stats.distanceMeters / 1000, duration, deviation.meanMeters, if (deviation.reachedEnd) "atteinte" else "pas atteinte",
        )
        else -> "%.2f km · %s".format(Locale.FRENCH, stats.distanceMeters / 1000, duration)
    }
}

private fun spaceSuffix(teams: List<Team>, name: String) = if (teams.isEmpty()) "" else " · $name"

private fun onlineSuffix(archived: Boolean) = if (archived) " · en ligne" else ""

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ItemCard(tag: String, title: String, line1: String, line2: String, onClick: () -> Unit, onShare: (() -> Unit)?, onDelete: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(line1, style = MaterialTheme.typography.bodyMedium)
                Text(line2, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                onShare?.let { TextButton(onClick = it) { Text("Partager") } }
                TextButton(onClick = onDelete) { Text("Supprimer") }
            }
        }
    }
}
