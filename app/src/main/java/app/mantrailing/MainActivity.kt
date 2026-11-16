package app.mantrailing

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mantrailing.ui.LoginScreen
import app.mantrailing.ui.PetPickerScreen
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
    val stored = state.stored
    when {
        !state.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        stored == null -> LoginScreen(state, onLogin = vm::login)
        stored.pet == null -> PetPickerScreen(state, onPick = vm::selectPet, onRetry = vm::loadPets, onLogout = vm::logout)
        else -> TrackScreen(
            state = state,
            pet = stored.pet,
            onRangeChange = vm::setRange,
            onLoad = vm::loadTrack,
            onLive = vm::setLive,
            onChangePet = vm::changePet,
            onLogout = vm::logout,
            onDismissMessage = vm::dismissMessage,
        )
    }
}

/** Orange de la trace du chien, repris en couleur principale. */
val DogTrackColor = Color(0xFFD9480F)

@Composable
private fun MantrailingTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFFF8A50), secondary = Color(0xFF9CCC65))
    } else {
        lightColorScheme(primary = DogTrackColor, secondary = Color(0xFF558B2F))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
