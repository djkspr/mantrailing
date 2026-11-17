package app.mantrailing.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mantrailing.gpx.GpxTrack
import app.mantrailing.session.Dog
import app.mantrailing.session.LaidTrail
import app.mantrailing.track.pathLengthMeters
import java.time.Instant

/**
 * Pistes que la trace importée a pu suivre : posées dans les 48 h avant son départ (5 min de tolérance d'horloge).
 * Sans heure dans le fichier : les pistes des 48 dernières heures.
 */
fun gpxTrailCandidates(track: GpxTrack, trails: List<LaidTrail>, now: Long = Instant.now().epochSecond): List<LaidTrail> {
    val start = track.startedAt ?: now
    return trails.filter { !it.archived && it.endedAt <= start + 300 && start - it.endedAt <= 48 * 3600 }
}

/** Fichier GPX lu : piste posée par le traceur, ou trace d'un chien (quel chien, quelle piste suivie). */
@Composable
fun GpxImportDialog(
    track: GpxTrack,
    dogs: List<Dog>,
    activeDogId: String?,
    trails: List<LaidTrail>,
    onTrail: () -> Unit,
    onSearch: (dogId: String?, trailId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var asTrail by rememberSaveable { mutableStateOf(false) }
    var dogId by rememberSaveable { mutableStateOf(activeDogId ?: dogs.firstOrNull()?.id) }
    var trailId by rememberSaveable { mutableStateOf<String?>(null) }
    val candidates = remember(track, trails) { gpxTrailCandidates(track, trails) }
    val length = remember(track) { track.segments.sumOf { pathLengthMeters(it) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importer « ${track.name ?: "trace GPX"} »") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    buildString {
                        append(formatDistance(length))
                        val start = track.startedAt
                        val end = track.endedAt
                        if (start != null && end != null) append(" · ${formatDuration(end - start)} · ${formatSessionDate(start)}")
                        else append(" · sans heures : datée de l'import")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("gpx-summary"),
                )
                Column(Modifier.selectableGroup()) {
                    Choice("Trace d'un chien", "Recherche faite avec un autre GPS (Garmin…)", !asTrail, "gpx-as-search") { asTrail = false }
                    Choice("Piste posée", "Parcours du traceur, que le chien suivra", asTrail, "gpx-as-trail") { asTrail = true }
                }
                if (!asTrail) {
                    if (dogs.isNotEmpty()) {
                        Text("Chien", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            dogs.forEach { dog -> FilterChip(selected = dog.id == dogId, onClick = { dogId = dog.id }, label = { Text(dog.name) }) }
                        }
                    }
                    Text("Piste suivie", style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.selectableGroup()) {
                        Choice("Sans piste", null, trailId == null, "gpx-no-trail") { trailId = null }
                        candidates.forEach { trail ->
                            Choice(
                                "Posée ${formatSessionDate(trail.startedAt)}", formatDistance(trailLength(trail)),
                                trailId == trail.id, "gpx-trail-${trail.id}",
                            ) { trailId = trail.id }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (asTrail) onTrail() else onSearch(dogId, trailId) }, modifier = Modifier.testTag("gpx-import")) { Text("Importer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun Choice(label: String, detail: String?, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 2.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
