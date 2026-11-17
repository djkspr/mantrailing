package app.mantrailing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import app.mantrailing.offline.OfflineDownload
import app.mantrailing.offline.OfflineZone
import app.mantrailing.offline.formatBytes

/** Paramètres : zones de carte téléchargées pour l'usage sans réseau. */
@Composable
fun OfflineMapsSetting(zones: List<OfflineZone>, download: OfflineDownload?, onDelete: (OfflineZone) -> Unit) {
    var deleting by remember { mutableStateOf<OfflineZone?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "En forêt, le réseau manque souvent : téléchargez avant de partir le plan IGN du terrain d'entraînement. " +
                "Sur l'accueil, cadrez la carte sur la zone, puis menu ⋮ › « Télécharger cette zone » (Wi-Fi conseillé).",
            style = MaterialTheme.typography.bodyMedium,
        )
        download?.let {
            Text("Téléchargement en cours : ${it.name} · ${it.percent} %", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (zones.isEmpty()) {
            Text("Aucune zone téléchargée.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("offline-empty"))
        }
        zones.forEach { zone ->
            Card(Modifier.fillMaxWidth().testTag("offline-zone-${zone.id}")) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(zone.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            formatBytes(zone.bytes) + if (zone.complete) "" else " · incomplète (réseau coupé pendant le téléchargement)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { deleting = zone }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Text(
            "Les zones regardées récemment restent aussi en mémoire (jusqu'à 200 Mo), même sans les avoir téléchargées.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    deleting?.let { zone ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer « ${zone.name} » ?") },
            text = { Text("La carte de cette zone ne s'affichera plus sans réseau. ${formatBytes(zone.bytes)} libérés.") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(zone) }, modifier = Modifier.testTag("offline-delete-confirm")) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }
}
