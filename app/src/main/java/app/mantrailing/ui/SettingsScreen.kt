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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
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
import app.mantrailing.session.Dog

const val PRIVACY_POLICY_URL = "https://djkspr.github.io/mantrailing-site/politique-confidentialite.html"

data class SettingsActions(
    val onBack: () -> Unit = {},
    val onLogin: (String, String) -> Unit = { _, _ -> },
    /** Réimporte les chiens du compte Tractive. */
    val onSyncDogs: () -> Unit = {},
    val onAddDog: (String) -> Unit = {},
    val onRenameDog: (Dog, String) -> Unit = { _, _ -> },
    val onDeleteDog: (Dog) -> Unit = {},
    val onSelectDog: (Dog) -> Unit = {},
    /** Partage un chien avec une équipe, ou le reprend en perso. */
    val onMoveDog: (Dog, String?) -> Unit = { _, _ -> },
    val onLogout: () -> Unit = {},
    val onTrackSource: (TrackSource) -> Unit = {},
    val onQuickNotes: (List<NoteType>) -> Unit = {},
    val cloud: CloudActions = CloudActions(),
)

@Composable
fun SettingsScreen(state: UiState, actions: SettingsActions) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.onBack, modifier = Modifier.semantics { contentDescription = "Retour" }) { Text("←", style = MaterialTheme.typography.headlineSmall) }
            Text("Paramètres", style = MaterialTheme.typography.headlineSmall)
        }

        DogsSetting(
            state.dogs, state.activeDogId, actions.onSelectDog, actions.onAddDog, actions.onRenameDog, actions.onDeleteDog,
            teams = state.cloud.teams, onMove = actions.onMoveDog,
        )

        HorizontalDivider()
        Text("Collier GPS Tractive", style = MaterialTheme.typography.titleMedium)
        if (state.tractive == null) {
            LoginForm(state, actions.onLogin)
        } else {
            val collars = state.dogs.filter { it.hasCollar }
            Card(Modifier.fillMaxWidth().testTag("tractive-account")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Compte Tractive connecté", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (collars.isEmpty()) "Aucun chien avec collier." else "Avec collier : ${collars.joinToString { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = actions.onSyncDogs, enabled = !state.busy) { Text("Actualiser les chiens") }
                        TextButton(onClick = actions.onLogout) { Text("Se déconnecter") }
                    }
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        HorizontalDivider()
        // Heure relue chaque minute pour « dernière synchronisation il y a… ».
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000)
                now = System.currentTimeMillis()
            }
        }
        CloudSetting(state.cloud, now, actions.cloud)

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
    // Sans choix enregistré : le collier s'il y en a un, sinon le téléphone (comme au démarrage d'une recherche).
    val collars = state.tractive != null && state.dogs.any { it.hasCollar }
    val selected = when {
        !collars -> TrackSource.PHONE
        else -> state.trackSource ?: TrackSource.TRACTIVE
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Trace du chien", style = MaterialTheme.typography.titleMedium)
        Text("Source utilisée pour enregistrer les recherches. Un chien sans collier est toujours suivi au téléphone.", style = MaterialTheme.typography.bodySmall)
        SourceOption(
            label = "Collier Tractive",
            detail = if (collars) "Le mode LIVE est activé automatiquement pendant la recherche." else "Connecte d'abord un compte Tractive ci-dessus.",
            selected = selected == TrackSource.TRACTIVE,
            enabled = collars,
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

