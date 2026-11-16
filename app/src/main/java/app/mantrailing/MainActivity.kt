package app.mantrailing

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mantrailing.recording.PhoneRecordingService
import app.mantrailing.session.StartDecision
import app.mantrailing.session.TrackSource
import app.mantrailing.session.availableTrails
import app.mantrailing.session.decideSource
import app.mantrailing.ui.TrailChoiceDialog
import java.time.Instant
import app.mantrailing.ui.SessionsActions
import app.mantrailing.ui.SessionsScreen
import app.mantrailing.ui.SettingsActions
import app.mantrailing.ui.SettingsScreen
import app.mantrailing.ui.SourceChoiceDialog
import app.mantrailing.ui.TrackActions
import app.mantrailing.ui.TrackScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MantrailingTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private enum class Screen { HOME, SETTINGS, SESSIONS }

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var askSource by remember { mutableStateOf(false) }
    var askTrail by remember { mutableStateOf(false) }
    // En attente de l'autorisation de localisation : pose de piste, ou recherche (choix à mémoriser, piste suivie).
    var pendingLayTrail by remember { mutableStateOf(false) }
    var rememberPendingChoice by remember { mutableStateOf(false) }
    var pendingTrailId by remember { mutableStateOf<String?>(null) }

    // Compte de démonstration saisi dans les paramètres, ou chien choisi : retour à la carte.
    LaunchedEffect(state.demo, state.stored?.pet) {
        if (screen == Screen.SETTINGS && (state.demo || state.stored?.pet != null)) screen = Screen.HOME
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        when {
            result[Manifest.permission.ACCESS_FINE_LOCATION] != true ->
                vm.showMessage("Sans l'autorisation de localisation précise, le téléphone ne peut rien enregistrer.")
            pendingLayTrail -> vm.startTrailLaying()
            else -> vm.startRecording(TrackSource.PHONE, rememberPendingChoice, pendingTrailId)
        }
    }

    fun requestLocation() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            // Notification d'enregistrement en cours (l'enregistrement fonctionne aussi sans).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    fun layTrail() {
        if (PhoneRecordingService.hasLocationPermission(context)) {
            vm.startTrailLaying()
        } else {
            pendingLayTrail = true
            requestLocation()
        }
    }

    fun startWith(source: TrackSource, remember: Boolean) {
        if (source == TrackSource.PHONE && !PhoneRecordingService.hasLocationPermission(context)) {
            pendingLayTrail = false
            rememberPendingChoice = remember
            requestLocation()
        } else {
            vm.startRecording(source, remember, pendingTrailId)
        }
    }

    /** Recherche sur [trailId] (ou sans piste) : source de la trace, proposée si besoin, puis départ. */
    fun beginSearch(trailId: String?) {
        pendingTrailId = trailId
        when (val decision = decideSource(state.trackSource, state.connected)) {
            StartDecision.AskSource -> askSource = true
            is StartDecision.Start -> startWith(decision.source, remember = false)
        }
    }

    when {
        !state.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        screen == Screen.SETTINGS -> {
            BackHandler { screen = Screen.HOME }
            SettingsScreen(
                state,
                SettingsActions(
                    onBack = { screen = Screen.HOME },
                    onLogin = vm::login,
                    onPickPet = vm::selectPet,
                    onRetryPets = vm::loadPets,
                    onChangePet = vm::changePet,
                    onLogout = vm::logout,
                    onTrackSource = vm::setTrackSource,
                    onQuickNotes = vm::setQuickNotes,
                ),
            )
        }
        screen == Screen.SESSIONS -> {
            BackHandler { screen = Screen.HOME }
            SessionsScreen(
                state.sessions,
                state.trails,
                SessionsActions(
                    onBack = { screen = Screen.HOME },
                    onOpen = { vm.openSession(it); screen = Screen.HOME },
                    onDelete = vm::deleteSession,
                    onOpenTrail = { vm.openTrail(it); screen = Screen.HOME },
                    onDeleteTrail = vm::deleteTrail,
                ),
            )
        }
        else -> TrackScreen(
            state,
            TrackActions(
                onRangeChange = vm::setRange,
                onLoad = vm::loadTrack,
                onLive = vm::setLive,
                onOpenSettings = { screen = Screen.SETTINGS },
                onStartDemo = vm::startDemo,
                onExitDemo = vm::exitDemo,
                onDismissMessage = vm::dismissMessage,
                onStartRecording = {
                    // Pistes posées récemment et pas encore travaillées : on demande laquelle le chien suit.
                    if (availableTrails(state.trails, state.sessions, Instant.now().epochSecond).isNotEmpty()) askTrail = true
                    else beginSearch(null)
                },
                onLayTrail = ::layTrail,
                onStartSearchOnTrail = { beginSearch(it.id) },
                onStopRecording = vm::stopRecording,
                onOpenSessions = { screen = Screen.SESSIONS },
                onCloseSession = vm::closeSession,
                onAddNote = vm::addNote,
                onUndoNote = vm::undoLastNote,
                onUpdateNote = vm::updateNote,
                onDeleteNote = vm::deleteNote,
            ),
        )
    }

    if (askTrail) {
        val now = Instant.now().epochSecond
        TrailChoiceDialog(
            trails = availableTrails(state.trails, state.sessions, now),
            now = now,
            onChoose = { trail -> askTrail = false; beginSearch(trail?.id) },
            onDismiss = { askTrail = false },
        )
    }

    if (askSource) {
        SourceChoiceDialog(
            onChoose = { source -> askSource = false; startWith(source, remember = true) },
            onDismiss = { askSource = false },
        )
    }
}

/** Orange de la trace du chien, repris en couleur principale. */
val DogTrackColor = Color(0xFFD9480F)

@Composable
fun MantrailingTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFFF8A50), secondary = Color(0xFF9CCC65))
    } else {
        lightColorScheme(primary = DogTrackColor, secondary = Color(0xFF558B2F))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
