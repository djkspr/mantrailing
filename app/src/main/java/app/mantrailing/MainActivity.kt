package app.mantrailing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mantrailing.ui.SettingsActions
import app.mantrailing.ui.SettingsScreen
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

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // Compte de démonstration saisi dans les paramètres, ou chien choisi : retour à la carte.
    LaunchedEffect(state.demo, state.stored?.pet) {
        if (state.demo || state.stored?.pet != null) settingsOpen = false
    }

    when {
        !state.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        settingsOpen -> {
            BackHandler { settingsOpen = false }
            SettingsScreen(
                state,
                SettingsActions(
                    onBack = { settingsOpen = false },
                    onLogin = vm::login,
                    onPickPet = vm::selectPet,
                    onRetryPets = vm::loadPets,
                    onChangePet = vm::changePet,
                    onLogout = vm::logout,
                ),
            )
        }
        else -> TrackScreen(
            state,
            TrackActions(
                onRangeChange = vm::setRange,
                onLoad = vm::loadTrack,
                onLive = vm::setLive,
                onOpenSettings = { settingsOpen = true },
                onStartDemo = vm::startDemo,
                onExitDemo = vm::exitDemo,
                onDismissMessage = vm::dismissMessage,
            ),
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
