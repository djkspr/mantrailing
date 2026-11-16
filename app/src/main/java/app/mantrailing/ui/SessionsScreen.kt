package app.mantrailing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mantrailing.session.TrailSession
import app.mantrailing.track.TrackStats
import java.util.Locale

data class SessionsActions(
    val onBack: () -> Unit = {},
    val onOpen: (TrailSession) -> Unit = {},
    val onDelete: (TrailSession) -> Unit = {},
)

/** « Mes pistes » : les pistes enregistrées, la plus récente d'abord. */
@Composable
fun SessionsScreen(sessions: List<TrailSession>, actions: SessionsActions) {
    var toDelete by remember { mutableStateOf<TrailSession?>(null) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.onBack, modifier = Modifier.semantics { contentDescription = "Retour" }) {
                Text("←", style = MaterialTheme.typography.headlineSmall)
            }
            Text("Mes pistes", style = MaterialTheme.typography.headlineSmall)
        }
        if (sessions.isEmpty()) {
            Text(
                "Aucune piste enregistrée. Sur l'accueil, « Démarrer une piste » au départ du chien, puis « Terminer la piste » à l'arrivée.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { session ->
                val stats = remember(session) { TrackStats.of(session.segments) }
                Card(onClick = { actions.onOpen(session) }, modifier = Modifier.fillMaxWidth().testTag("session-${session.id}")) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(formatSessionDate(session.startedAt).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                            Text("${session.dogName} · ${sourceLabel(session.source)}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (stats.pointCount == 0) {
                                    "Trace pas encore récupérée · ${formatDuration(session.endedAt - session.startedAt)}"
                                } else {
                                    "%.2f km · %s".format(Locale.FRENCH, stats.distanceMeters / 1000, formatDuration(session.endedAt - session.startedAt))
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { toDelete = session }) { Text("Supprimer") }
                    }
                }
            }
        }
    }

    toDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer la piste ?") },
            text = { Text("La piste du ${formatSessionDate(session.startedAt)} sera supprimée de ce téléphone.") },
            confirmButton = {
                TextButton(onClick = { actions.onDelete(session); toDelete = null }) { Text("Supprimer définitivement") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
}
