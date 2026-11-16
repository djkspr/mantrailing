package app.mantrailing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import app.mantrailing.UiState
import app.mantrailing.session.NoteType
import app.mantrailing.session.TrackSource
import app.mantrailing.tractive.Pet

const val PRIVACY_POLICY_URL = "https://djkspr.github.io/mantrailing-site/politique-confidentialite.html"

data class SettingsActions(
    val onBack: () -> Unit = {},
    val onLogin: (String, String) -> Unit = { _, _ -> },
    val onPickPet: (Pet) -> Unit = {},
    val onRetryPets: () -> Unit = {},
    val onChangePet: () -> Unit = {},
    val onLogout: () -> Unit = {},
    val onTrackSource: (TrackSource) -> Unit = {},
    val onQuickNotes: (List<NoteType>) -> Unit = {},
)

@Composable
fun SettingsScreen(state: UiState, actions: SettingsActions) {
    val stored = state.stored
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.onBack, modifier = Modifier.semantics { contentDescription = "Retour" }) { Text("←", style = MaterialTheme.typography.headlineSmall) }
            Text("Paramètres", style = MaterialTheme.typography.headlineSmall)
        }

        Text("Collier GPS Tractive", style = MaterialTheme.typography.titleMedium)
        when {
            stored == null -> LoginForm(state, actions.onLogin)
            stored.pet == null -> PetPicker(state, actions)
            else -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connecté : ${stored.pet.name}", style = MaterialTheme.typography.titleMedium)
                    Text(stored.pet.trackerId?.let { "Collier $it" } ?: "Pas de collier associé", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = actions.onChangePet) { Text("Changer de chien") }
                        TextButton(onClick = actions.onLogout) { Text("Se déconnecter") }
                    }
                }
            }
        }

        HorizontalDivider()
        TrackSourceSetting(state, actions.onTrackSource)

        HorizontalDivider()
        QuickNotesSetting(state.quickNotes, actions.onQuickNotes)

        HorizontalDivider()
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }) { Text("Politique de confidentialité") }
        Text(
            "Application indépendante, ni affiliée à Tractive, ni approuvée par Tractive.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackSourceSetting(state: UiState, onSelect: (TrackSource) -> Unit) {
    // Sans choix enregistré : le collier s'il est connecté, sinon le téléphone (comme au démarrage d'une piste).
    val selected = when {
        !state.connected -> TrackSource.PHONE
        else -> state.trackSource ?: TrackSource.TRACTIVE
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Trace du chien", style = MaterialTheme.typography.titleMedium)
        Text("Source utilisée pour enregistrer les pistes.", style = MaterialTheme.typography.bodySmall)
        SourceOption(
            label = "Collier Tractive",
            detail = if (state.connected) "Le mode LIVE est activé automatiquement pendant la piste." else "Connecte d'abord un collier ci-dessus.",
            selected = selected == TrackSource.TRACTIVE,
            enabled = state.connected,
            onClick = { onSelect(TrackSource.TRACTIVE) },
        )
        SourceOption(
            label = "GPS du téléphone",
            detail = "Le téléphone du maître-chien, à quelques mètres du chien au bout de la longe.",
            selected = selected == TrackSource.PHONE,
            enabled = true,
            onClick = { onSelect(TrackSource.PHONE) },
        )
    }
}

@Composable
private fun SourceOption(label: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoginForm(state: UiState, onLogin: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val canSubmit = email.isNotBlank() && password.isNotEmpty() && !state.busy

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Connecte ton compte Tractive pour récupérer la trace GPS du collier. " +
                "Le mot de passe n'est pas conservé : seul un jeton de connexion reste sur le téléphone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email Tractive") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("email"),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Mot de passe") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("password"),
        )
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { onLogin(email, password) }, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Se connecter")
        }
    }
}

@Composable
private fun PetPicker(state: UiState, actions: SettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Quel chien ?", style = MaterialTheme.typography.bodyLarge)
        when {
            state.busy -> CircularProgressIndicator()
            state.pets.isEmpty() -> {
                Text(state.message ?: "Aucun animal trouvé sur ce compte Tractive.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.onRetryPets) { Text("Réessayer") }
                    TextButton(onClick = actions.onLogout) { Text("Se déconnecter") }
                }
            }
            else -> state.pets.forEach { pet ->
                Card(onClick = { actions.onPickPet(pet) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(pet.name, style = MaterialTheme.typography.titleMedium)
                        Text(pet.trackerId?.let { "Collier $it" } ?: "Pas de collier associé", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
