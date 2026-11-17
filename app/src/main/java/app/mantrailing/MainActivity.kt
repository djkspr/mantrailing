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
import androidx.compose.runtime.key
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
import androidx.lifecycle.lifecycleScope
import app.mantrailing.ui.UpdateActions
import app.mantrailing.update.AppUpdates
import app.mantrailing.update.UpdateState
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import kotlinx.coroutines.launch
import app.mantrailing.ui.CloudActions
import app.mantrailing.ui.SpaceChoiceDialog
import app.mantrailing.ui.SessionsActions
import app.mantrailing.ui.SessionsScreen
import app.mantrailing.ui.SettingsActions
import app.mantrailing.ui.SettingsScreen
import app.mantrailing.ui.SourceChoiceDialog
import app.mantrailing.ui.GpxImportDialog
import app.mantrailing.ui.TrainingLogDialog
import app.mantrailing.session.SCENT_ARTICLE_SUGGESTIONS
import app.mantrailing.session.logSuggestions
import app.mantrailing.ui.HelpScreen
import app.mantrailing.ui.TrackActions
import app.mantrailing.ui.TrackScreen

class MainActivity : ComponentActivity() {
    // Mises à jour proposées par Google Play (version installée depuis le Play Store).
    private lateinit var updates: AppUpdates
    private val updateLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        // Refus ou abandon dans la fenêtre de Google Play : la mise à jour reste proposée à la prochaine vérification.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updates = AppUpdates(AppUpdateManagerFactory.create(this))
        setContent {
            MantrailingTheme {
                Surface(Modifier.fillMaxSize()) {
                    val update by updates.state.collectAsStateWithLifecycle()
                    App(
                        update = update,
                        updateActions = UpdateActions(
                            onUpdate = { updates.start(updateLauncher) },
                            onInstall = updates::install,
                        ),
                    )
                }
            }
        }
    }

    // À l'ouverture et à chaque retour dans l'appli : une version plus récente ? un téléchargement terminé entre-temps ?
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { updates.check() }
    }

    override fun onDestroy() {
        updates.dispose()
        super.onDestroy()
    }
}

private enum class Screen { HOME, SETTINGS, SESSIONS, HELP }

@Composable
private fun App(
    vm: MainViewModel = viewModel(),
    update: UpdateState = UpdateState.None,
    updateActions: UpdateActions = UpdateActions(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // « Plus tard » : plus proposé avant la prochaine ouverture de l'appli (le téléchargement en cours reste affiché).
    var updateLater by rememberSaveable { mutableStateOf(false) }
    val shownUpdate = if (updateLater && update is UpdateState.Available) UpdateState.None else update
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    // Écran d'où l'aide a été ouverte (accueil ou paramètres) : le retour y ramène.
    var helpFrom by rememberSaveable { mutableStateOf(Screen.HOME) }
    fun openHelp(from: Screen) {
        helpFrom = from
        screen = Screen.HELP
    }
    var askSource by remember { mutableStateOf(false) }
    var askTrail by remember { mutableStateOf(false) }
    // En attente de l'autorisation de localisation : pose de piste, ou recherche (choix à mémoriser, piste suivie).
    var pendingLayTrail by remember { mutableStateOf(false) }
    var rememberPendingChoice by remember { mutableStateOf(false) }
    var pendingTrailId by remember { mutableStateOf<String?>(null) }
    // Espace (perso ou équipe) de la piste ou recherche à démarrer.
    var pendingSpaceId by remember { mutableStateOf<String?>(null) }
    // Choix de l'espace demandé : pour poser une piste (true) ou pour une recherche (false).
    var askSpaceForTrail by remember { mutableStateOf<Boolean?>(null) }
    var spaceDefault by remember { mutableStateOf<String?>(null) }

    // Compte de démonstration saisi dans les paramètres : retour à la carte.
    LaunchedEffect(state.demo) {
        if (screen == Screen.SETTINGS && state.demo) screen = Screen.HOME
    }

    // Fichier GPX choisi dans le téléphone (Téléchargements, Drive…) : lu, puis on demande ce que c'est.
    val gpxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importGpx) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        when {
            result[Manifest.permission.ACCESS_FINE_LOCATION] != true ->
                vm.showMessage("Sans l'autorisation de localisation précise, le téléphone ne peut rien enregistrer.")
            pendingLayTrail -> vm.startTrailLaying(pendingSpaceId)
            else -> vm.startRecording(TrackSource.PHONE, rememberPendingChoice, pendingTrailId, pendingSpaceId)
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

    fun layTrailIn(spaceId: String?) {
        pendingSpaceId = spaceId
        if (PhoneRecordingService.hasLocationPermission(context)) {
            vm.startTrailLaying(spaceId)
        } else {
            pendingLayTrail = true
            requestLocation()
        }
    }

    /** Pose d'une piste ; avec des équipes, on demande d'abord pour qui. */
    fun layTrail() {
        if (state.cloud.teams.isEmpty()) {
            layTrailIn(null)
        } else {
            spaceDefault = state.lastSpaceId?.takeIf { id -> state.cloud.teams.any { it.id == id } }
            askSpaceForTrail = true
        }
    }

    fun startWith(source: TrackSource, remember: Boolean) {
        if (source == TrackSource.PHONE && !PhoneRecordingService.hasLocationPermission(context)) {
            pendingLayTrail = false
            rememberPendingChoice = remember
            requestLocation()
        } else {
            vm.startRecording(source, remember, pendingTrailId, pendingSpaceId)
        }
    }

    /** Recherche dans l'espace [spaceId] : source de la trace, proposée si besoin, puis départ. */
    fun searchIn(spaceId: String?) {
        pendingSpaceId = spaceId
        when (val decision = decideSource(state.trackSource, state.collarReady)) {
            StartDecision.AskSource -> askSource = true
            is StartDecision.Start -> startWith(decision.source, remember = false)
        }
    }

    /**
     * Recherche sur [trailId] (ou sans piste). Avec des équipes, on demande pour qui : par défaut l'équipe
     * de la piste suivie, sinon celle de la dernière fois.
     */
    fun beginSearch(trailId: String?) {
        pendingTrailId = trailId
        if (state.cloud.teams.isEmpty()) {
            searchIn(null)
        } else {
            val trailSpace = state.trails.find { it.id == trailId }?.spaceId
            spaceDefault = (if (trailId != null) trailSpace else state.lastSpaceId)?.takeIf { id -> state.cloud.teams.any { it.id == id } }
            askSpaceForTrail = false
        }
    }

    when {
        !state.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        screen == Screen.HELP -> {
            BackHandler { screen = helpFrom }
            HelpScreen(onBack = { screen = helpFrom }, onReplayTour = { vm.replayTour(); screen = Screen.HOME })
        }
        screen == Screen.SETTINGS -> {
            BackHandler { screen = Screen.HOME }
            SettingsScreen(
                state,
                SettingsActions(
                    onBack = { screen = Screen.HOME },
                    onReplayTour = { vm.replayTour(); screen = Screen.HOME },
                    onLogin = vm::login,
                    onSyncDogs = vm::syncTractiveDogs,
                    onAddDog = vm::addDog,
                    onRenameDog = vm::renameDog,
                    onDeleteDog = vm::deleteDog,
                    onSelectDog = vm::selectDog,
                    onMoveDog = vm::moveDog,
                    onLogout = vm::logout,
                    onWeenectLogin = vm::loginWeenect,
                    onWeenectLogout = vm::logoutWeenect,
                    onSyncWeenect = vm::syncWeenectDogs,
                    onTrackSource = vm::setTrackSource,
                    onQuickNotes = vm::setQuickNotes,
                    onOpenHelp = { openHelp(Screen.SETTINGS) },
                    cloud = CloudActions(
                        // Le choix du compte Google s'affiche au-dessus de l'activité.
                        onSignIn = { vm.signInCloud(context) },
                        onSignOut = vm::signOutCloud,
                        onDelete = { vm.deleteCloudData(context) },
                        onWifiOnly = vm::setSyncWifiOnly,
                        onSyncNow = vm::syncNow,
                        onKeepDays = vm::setKeepDays,
                        onCreateTeam = vm::createTeam,
                        onInvite = vm::createInvite,
                        onDismissInvite = vm::dismissInvite,
                        onJoin = vm::joinTeam,
                        onLeave = vm::leaveTeam,
                    ),
                ),
            )
        }
        screen == Screen.SESSIONS -> {
            BackHandler { screen = Screen.HOME }
            SessionsScreen(
                state.sessions,
                state.trails,
                state.dogs,
                SessionsActions(
                    onBack = { screen = Screen.HOME },
                    onOpen = { vm.openSession(it); screen = Screen.HOME },
                    onDelete = vm::deleteSession,
                    onOpenTrail = { vm.openTrail(it); screen = Screen.HOME },
                    onDeleteTrail = vm::deleteTrail,
                    onMoveSession = vm::moveSession,
                    onMoveTrail = vm::moveTrail,
                ),
                teams = state.cloud.teams,
            )
        }
        else -> TrackScreen(
            state,
            TrackActions(
                onRangeChange = vm::setRange,
                onLoad = vm::loadTrack,
                onLive = vm::setLive,
                onOpenSettings = { screen = Screen.SETTINGS },
                onSelectDog = vm::selectDog,
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
                onOpenHelp = { openHelp(Screen.HOME) },
                onCloseSession = vm::closeSession,
                onExportPdf = vm::exportPdf,
                onExportGpx = vm::exportGpx,
                onOpenLog = vm::openLogEditor,
                onFinishTour = vm::finishTour,
                // Le type MIME d'un .gpx varie selon l'appli qui l'a enregistré : tous les fichiers sont proposés.
                onImportGpx = { gpxPicker.launch(arrayOf("*/*")) },
                onAddNote = vm::addNote,
                onUndoNote = vm::undoLastNote,
                onUpdateNote = vm::updateNote,
                onDeleteNote = vm::deleteNote,
                onCommentLastNote = vm::commentLastNote,
            ),
            update = shownUpdate,
            updateActions = updateActions.copy(onLater = { updateLater = true }),
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

    askSpaceForTrail?.let { forTrail ->
        SpaceChoiceDialog(
            title = if (forTrail) "Piste posée pour…" else "Recherche pour…",
            teams = state.cloud.teams,
            selected = spaceDefault,
            onChoose = { spaceId -> askSpaceForTrail = null; if (forTrail) layTrailIn(spaceId) else searchIn(spaceId) },
            onDismiss = { askSpaceForTrail = null },
        )
    }

    if (askSource) {
        SourceChoiceDialog(
            onChoose = { source -> askSource = false; startWith(source, remember = true) },
            onDismiss = { askSource = false },
            brand = if (state.collarSourceOf(state.activeDog) == TrackSource.WEENECT) "Weenect" else "Tractive",
        )
    }

    // Carnet d'entraînement : proposé en fin de pose ou de recherche, ou ouvert avec « Carnet ».
    state.logEditor?.let { target ->
        val logs = state.sessions.map { it.log } + state.trails.map { it.log }
        val initial = if (target.trail) state.trails.find { it.id == target.id }?.log else state.sessions.find { it.id == target.id }?.log
        key(target) {
            TrainingLogDialog(
                initial = initial,
                forTrail = target.trail,
                runnerSuggestions = logSuggestions(logs, { it.runner }),
                scentSuggestions = logSuggestions(logs, { it.scentArticle }, SCENT_ARTICLE_SUGGESTIONS),
                placeSuggestions = logSuggestions(logs, { it.place }),
                onSave = vm::saveLog,
                onDismiss = vm::closeLogEditor,
            )
        }
    }

    state.gpxImport?.let { track ->
        GpxImportDialog(
            track, state.dogs, state.activeDogId, state.trails,
            onTrail = vm::importGpxAsTrail,
            onSearch = vm::importGpxAsSearch,
            onDismiss = vm::cancelGpxImport,
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
