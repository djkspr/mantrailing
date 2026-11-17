package app.mantrailing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.mantrailing.update.UpdateState

data class UpdateActions(
    /** Télécharger la nouvelle version (confirmation de Google Play). */
    val onUpdate: () -> Unit = {},
    /** Installer la version téléchargée : l'appli redémarre. */
    val onInstall: () -> Unit = {},
    /** Ne plus proposer avant la prochaine ouverture de l'appli. */
    val onLater: () -> Unit = {},
)

/**
 * Bandeau de mise à jour sur l'accueil. [recording] : un enregistrement est en cours ; l'installation, qui redémarre
 * l'appli, attend alors sa fin.
 */
@Composable
fun UpdateBanner(state: UpdateState, recording: Boolean, actions: UpdateActions) {
    if (state == UpdateState.None) return
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("update-banner")) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 4.dp)) {
            when (state) {
                is UpdateState.Available -> {
                    Text("Une nouvelle version de Mantrailing est disponible.", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.align(Alignment.End)) {
                        TextButton(onClick = actions.onLater) { Text("Plus tard") }
                        TextButton(onClick = actions.onUpdate) { Text("Mettre à jour") }
                    }
                }
                is UpdateState.Downloading -> {
                    Text("Téléchargement de la mise à jour… ${state.percent} %", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp, bottom = 8.dp),
                    )
                }
                UpdateState.Downloaded -> {
                    if (recording) {
                        Text(
                            "Mise à jour prête : vous pourrez redémarrer à la fin de l'enregistrement.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Mise à jour prête.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = actions.onInstall) { Text("Redémarrer") }
                        }
                    }
                }
                UpdateState.None -> Unit
            }
        }
    }
}
