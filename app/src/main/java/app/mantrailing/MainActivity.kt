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
import app.mantrailing.session.decideSource
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
    // Choix à mémoriser une fois l'autorisation de localisation accordée.
    var rememberPendingChoice by remember { mutableStateOf(false) }

    // Compte de démonstration saisi dans les paramètres, ou chien choisi : retour à la carte.
    LaunchedEffect(state.demo, state.stored?.pet) {
        if (screen == Screen.SETTINGS && (state.demo || state.stored?.pet != null)) screen = Screen.HOME
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            vm.startRecording(TrackSource.PHONE, rememberPendingChoice)
        } else {
            vm.showMessage("Sans l'autorisation de localisation précise, le téléphone ne peut pas enregistrer la piste.")
        }
    }

    fun startWith(source: TrackSource, remember: Boolean) {
        if (source == TrackSource.PHONE && !PhoneRecordingService.hasLocationPermission(context)) {
            rememberPendingChoice = remember
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                // Notification « Piste en cours » (l'enregistrement fonctionne aussi sans).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            vm.startRecording(source, remember)
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
                ),
            )
        }
        screen == Screen.SESSIONS -> {
            BackHandler { screen = Screen.HOME }
            SessionsScreen(
                state.sessions,
                SessionsActions(
                    onBack = { screen = Screen.HOME },
                    onOpen = { vm.openSession(it); screen = Screen.HOME },
                    onDelete = vm::deleteSession,
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
                    when (val decision = decideSource(state.trackSource, state.connected)) {
                        StartDecision.AskSource -> askSource = true
                        is StartDecision.Start -> startWith(decision.source, remember = false)
                    }
                },
                onStopRecording = vm::stopRecording,
                onOpenSessions = { screen = Screen.SESSIONS },
                onCloseSession = vm::closeSession,
            ),
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
