package app.mantrailing.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import app.mantrailing.BuildConfig
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
    val onWeenectLogin: (String, String) -> Unit = { _, _ -> },
    val onWeenectLogout: () -> Unit = {},
    /** Réimporte les traceurs du compte Weenect. */
    val onSyncWeenect: () -> Unit = {},
    val onTrackSource: (TrackSource) -> Unit = {},
    val onQuickNotes: (List<NoteType>) -> Unit = {},
    val cloud: CloudActions = CloudActions(),
)

/** Rubriques des paramètres : chacune a sa page, le menu les résume en une ligne. */
enum class SettingsPage(val title: String, val tag: String) {
    DOGS("Chiens", "dogs"),
    COLLARS("Colliers GPS", "collars"),
    BACKUP("Sauvegarde en ligne", "backup"),
    TEAMS("Équipes", "teams"),
    NOTES("Notes rapides", "notes"),
    ABOUT("À propos", "about"),
}

/** Résumé d'une rubrique, affiché sous son nom dans le menu. */
fun settingsSummary(page: SettingsPage, state: UiState, now: Long): String = when (page) {
    SettingsPage.DOGS -> when {
        state.dogs.isEmpty() -> "Aucun chien : ajoutez-les pour séparer leurs recherches"
        else -> state.dogs.joinToString { if (it.id == state.activeDogId && state.dogs.size > 1) "${it.name} (actif)" else it.name }
    }
    SettingsPage.COLLARS -> {
        val accounts = listOfNotNull("Tractive".takeIf { state.tractive != null }, "Weenect".takeIf { state.weenect != null })
        val collars = state.dogs.any { state.collarSourceOf(it) != null }
        val source = if (collars && state.trackSource != TrackSource.PHONE) "trace par le collier" else "trace par le téléphone"
        (if (accounts.isEmpty()) "Aucun collier connecté" else accounts.joinToString(" et ") + " connecté" + if (accounts.size > 1) "s" else "") + " · $source"
    }
    SettingsPage.BACKUP -> when {
        !state.cloud.available -> "Pas encore disponible"
        state.cloud.email == null -> "Désactivée"
        else -> "Activée" + (if (state.cloud.wifiOnly) " (Wi-Fi seulement)" else "") + " · " +
            syncStatus(state.cloud, now).removeSuffix(".").replaceFirstChar { it.lowercase() }
    }
    SettingsPage.TEAMS -> when {
        state.cloud.email == null -> "Avec la sauvegarde en ligne : famille, club…"
        state.cloud.teams.isEmpty() -> "Aucune équipe"
        else -> state.cloud.teams.joinToString { it.name }
    }
    SettingsPage.NOTES -> "${state.quickNotes.size} bouton${if (state.quickNotes.size > 1) "s" else ""} pendant la recherche"
    SettingsPage.ABOUT -> "Confidentialité, version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}

/**
 * Paramètres : un menu court (une ligne par rubrique, avec son état), chaque rubrique sur sa propre page.
 * [initialPage] : page ouverte directement (tests, scènes de présentation).
 */
@Composable
fun SettingsScreen(state: UiState, actions: SettingsActions, initialPage: SettingsPage? = null) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    // Retour du téléphone : d'une rubrique vers le menu.
    BackHandler(enabled = page != null) { page = null }
    // Heure relue chaque minute pour « synchronisé il y a… ».
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { if (page != null) page = null else actions.onBack() },
                modifier = Modifier.semantics { contentDescription = "Retour" },
            ) { Text("←", style = MaterialTheme.typography.headlineSmall) }
            Text(page?.title ?: "Paramètres", style = MaterialTheme.typography.headlineSmall)
        }
        when (page) {
            null -> SettingsMenu(state, now) { page = it }
            SettingsPage.DOGS -> DogsSetting(
                state.dogs, state.activeDogId, actions.onSelectDog, actions.onAddDog, actions.onRenameDog, actions.onDeleteDog,
                teams = state.cloud.teams, onMove = actions.onMoveDog,
            )
            SettingsPage.COLLARS -> CollarsSetting(state, actions)
            SettingsPage.BACKUP -> CloudSetting(state.cloud, now, actions.cloud)
            SettingsPage.TEAMS -> if (state.cloud.email == null) {
                Text(
                    "Les équipes passent par la sauvegarde en ligne : activez-la d'abord pour créer ou rejoindre une équipe.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { page = SettingsPage.BACKUP }) { Text("Sauvegarde en ligne") }
            } else {
                TeamsSetting(state.cloud, actions.cloud)
            }
            SettingsPage.NOTES -> QuickNotesSetting(state.quickNotes, actions.onQuickNotes)
            SettingsPage.ABOUT -> AboutSetting()
        }
    }
}

@Composable
private fun SettingsMenu(state: UiState, now: Long, onOpen: (SettingsPage) -> Unit) {
    Column {
        SettingsPage.entries.forEachIndexed { i, page ->
            if (i > 0) HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(page) }.padding(vertical = 14.dp).testTag("settings-${page.tag}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(page.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        settingsSummary(page, state, now),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Colliers GPS : comptes des fabricants, et la source utilisée pour la trace du chien. */
@Composable
private fun CollarsSetting(state: UiState, actions: SettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Un seul message pour les deux comptes (connexion refusée, chiens importés…).
        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("collars-message"))
        }
        Text("Tractive", style = MaterialTheme.typography.titleMedium)
        if (state.tractive == null) {
            LoginForm(state, actions.onLogin, brand = "Tractive", tagPrefix = "")
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
                }
            }
        }
        HorizontalDivider()
        Text("Weenect", style = MaterialTheme.typography.titleMedium)
        if (state.weenect == null) {
            LoginForm(state, actions.onWeenectLogin, brand = "Weenect", tagPrefix = "weenect-")
        } else {
            val collars = state.dogs.filter { it.weenectTrackerId != null }
            Card(Modifier.fillMaxWidth().testTag("weenect-account")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Compte Weenect connecté", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (collars.isEmpty()) "Aucun chien avec traceur." else "Avec traceur : ${collars.joinToString { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = actions.onSyncWeenect, enabled = !state.busy) { Text("Actualiser les chiens") }
                        TextButton(onClick = actions.onWeenectLogout) { Text("Se déconnecter") }
                    }
                }
            }
        }
        Text(
            "Garmin Alpha ou autre GPS : exportez la trace en fichier GPX, puis « Importer un fichier GPX » depuis le menu de l'accueil.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        TrackSourceSetting(state, actions.onTrackSource)
    }
}

@Composable
private fun AboutSetting() {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mantrailing ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }) { Text("Politique de confidentialité") }
        Text(
            "Application indépendante, ni affiliée à Tractive ni à Weenect, ni approuvée par eux.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackSourceSetting(state: UiState, onSelect: (TrackSource) -> Unit) {
    // Sans choix enregistré : le collier s'il y en a un, sinon le téléphone (comme au démarrage d'une recherche).
    val collars = state.dogs.any { state.collarSourceOf(it) != null }
    val selected = when {
        !collars -> TrackSource.PHONE
        else -> state.trackSource ?: TrackSource.TRACTIVE
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Trace du chien", style = MaterialTheme.typography.titleMedium)
        Text("Source utilisée pour enregistrer les recherches. Un chien sans collier est toujours suivi au téléphone.", style = MaterialTheme.typography.bodySmall)
        SourceOption(
            label = "Collier du chien",
            detail = if (collars) "Tractive (mode LIVE) ou Weenect (super live), activé automatiquement pendant la recherche." else "Connecte d'abord un compte Tractive ou Weenect ci-dessus.",
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
private fun LoginForm(state: UiState, onLogin: (String, String) -> Unit, brand: String, tagPrefix: String) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val canSubmit = email.isNotBlank() && password.isNotEmpty() && !state.busy

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Connecte ton compte $brand pour récupérer la trace GPS du collier. " +
                "Le mot de passe n'est pas conservé : seul un jeton de connexion reste sur le téléphone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email $brand") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}email"),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Mot de passe") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}password"),
        )
        Button(onClick = { onLogin(email, password) }, enabled = canSubmit, modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}login")) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Se connecter")
        }
    }
}

