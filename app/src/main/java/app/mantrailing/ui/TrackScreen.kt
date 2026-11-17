package app.mantrailing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mantrailing.TimeRange
import app.mantrailing.UiState
import app.mantrailing.demo.DemoSession
import app.mantrailing.session.Dog
import app.mantrailing.session.LAYING_CATEGORIES
import app.mantrailing.session.LAYING_QUICK_NOTES
import app.mantrailing.session.SEARCH_CATEGORIES
import app.mantrailing.update.UpdateState
import app.mantrailing.session.LaidTrail
import app.mantrailing.session.NoteCategory
import app.mantrailing.session.NoteType
import app.mantrailing.session.SearchNote
import app.mantrailing.session.RecordingKind
import app.mantrailing.session.TrackSource
import app.mantrailing.track.TrackStats
import app.mantrailing.track.TrailDeviation
import app.mantrailing.track.distanceMeters
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

data class TrackActions(
    val onRangeChange: (TimeRange) -> Unit = {},
    val onLoad: () -> Unit = {},
    val onLive: (Boolean) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    /** Choisir le chien qui va chercher. */
    val onSelectDog: (Dog) -> Unit = {},
    val onStartDemo: () -> Unit = {},
    val onExitDemo: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
    /** Démarrer la recherche du chien. */
    val onStartRecording: () -> Unit = {},
    /** Le traceur commence à poser la piste. */
    val onLayTrail: () -> Unit = {},
    /** Démarrer la recherche directement sur la piste affichée. */
    val onStartSearchOnTrail: (LaidTrail) -> Unit = {},
    /** Terminer l'enregistrement ; pour une recherche, avec son issue (Trouvé…) ajoutée aux notes. */
    val onStopRecording: (NoteType?) -> Unit = {},
    val onOpenSessions: () -> Unit = {},
    val onCloseSession: () -> Unit = {},
    val onAddNote: (NoteType, String?) -> Unit = { _, _ -> },
    val onUndoNote: () -> Unit = {},
    val onUpdateNote: (SearchNote) -> Unit = {},
    val onDeleteNote: (SearchNote) -> Unit = {},
    /** Précision ajoutée à la dernière note posée (danger, point d'attention du traceur). */
    val onCommentLastNote: (String) -> Unit = {},
)

/** Écran d'accueil : la carte, la pose d'une piste, la recherche en cours, ou une piste / recherche enregistrée. */
@Composable
fun TrackScreen(
    state: UiState,
    actions: TrackActions,
    update: UpdateState = UpdateState.None,
    updateActions: UpdateActions = UpdateActions(),
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showPeriod by rememberSaveable { mutableStateOf(false) }
    var showAllNotes by rememberSaveable { mutableStateOf(false) }
    var showNotesList by rememberSaveable { mutableStateOf(false) }
    var editedNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var askOutcome by rememberSaveable { mutableStateOf(false) }
    // Signalement du traceur à préciser, et signalement affiché (lecture seule).
    var detailFor by rememberSaveable { mutableStateOf<NoteType?>(null) }
    var trailNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    // Départ du chien, pour situer les notes dans le temps.
    val notesStart = state.openedSession?.startedAt ?: DemoSession.segments.first().first().time
    val title = when {
        state.demo -> "${DemoSession.DOG_NAME} (démo)"
        state.openedSession != null -> state.openedSession.dogName
        state.openedTrail != null -> "Piste posée"
        state.recording != null && state.recording.kind == RecordingKind.SEARCH -> state.recording.dogName
        else -> state.activeDog?.name ?: "Mantrailing"
    }
    // Accueil sans rien d'ouvert : le titre permet de choisir le chien qui va chercher.
    val choosingDog = state.dogs.isNotEmpty() && !state.demo && state.recording == null &&
        state.openedSession == null && state.openedTrail == null
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (choosingDog) {
                    DogPicker(state.dogs, state.activeDog, actions.onSelectDog, actions.onOpenSettings, Modifier.weight(1f))
                } else {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                if (state.connected && !state.demo && state.recording == null) {
                    OutlinedButton(onClick = { actions.onLive(true) }, enabled = !state.busy) { Text("Activer LIVE") }
                }
                Box {
                    TextButton(onClick = { menuOpen = true }, modifier = Modifier.semantics { contentDescription = "Menu" }) { Text("⋮") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Mes pistes") }, onClick = { menuOpen = false; actions.onOpenSessions() })
                        if (state.connected && !state.demo) {
                            DropdownMenuItem(
                                text = { Text(if (showPeriod) "Masquer la recherche par période" else "Trace du collier sur une période") },
                                onClick = { menuOpen = false; showPeriod = !showPeriod },
                            )
                            DropdownMenuItem(text = { Text("Arrêter le LIVE") }, onClick = { menuOpen = false; actions.onLive(false) })
                        }
                        if (state.demo) {
                            DropdownMenuItem(text = { Text("Quitter la démo") }, onClick = { menuOpen = false; actions.onExitDemo() })
                        } else if (state.recording == null) {
                            DropdownMenuItem(text = { Text("Séance de démonstration") }, onClick = { menuOpen = false; actions.onStartDemo() })
                        }
                        DropdownMenuItem(text = { Text("Paramètres") }, onClick = { menuOpen = false; actions.onOpenSettings() })
                    }
                }
            }
            UpdateBanner(update, recording = state.recording != null, actions = updateActions)
            when {
                state.recording != null -> {
                    RecordingBanner(state) {
                        // Issue de la recherche demandée, sauf si elle a déjà été notée.
                        val searching = state.recording.kind == RecordingKind.SEARCH
                        if (searching && state.recordingNotes.none { it.type.category == NoteCategory.END }) askOutcome = true
                        else actions.onStopRecording(null)
                    }
                    if (state.recording.kind == RecordingKind.SEARCH) {
                        QuickNotesBar(
                            state.quickNotes, state.recordingNotes, state.recording.startedAt,
                            onAdd = { actions.onAddNote(it, null) }, onOther = { showAllNotes = true }, onUndo = actions.onUndoNote,
                        )
                    } else {
                        // Le traceur signale ce que le maître-chien devra savoir ; précision proposée pour un danger.
                        QuickNotesBar(
                            LAYING_QUICK_NOTES, state.recordingNotes, state.recording.startedAt,
                            onAdd = { type ->
                                actions.onAddNote(type, null)
                                if (type.isWarning) detailFor = type
                            },
                            onOther = { showAllNotes = true }, onUndo = actions.onUndoNote,
                        )
                    }
                }
                state.demo -> InfoBar(
                    "Séance de démonstration : données fictives. Piste de ${formatDuration(DemoSession.segments.first().first().time - DemoSession.trailEndedAt)}.",
                    "Quitter" to actions.onExitDemo,
                )
                state.openedSession != null -> InfoBar(
                    buildString {
                        append("Recherche du ${formatSessionDate(state.openedSession.startedAt)} · ${sourceLabel(state.openedSession.source)}")
                        state.openedSession.trailAgeSeconds?.let { append(" · piste de ${formatDuration(it)}") }
                        state.openedSession.weather?.let { append("\nMétéo au départ : ${it.summary}") }
                        state.openedSession.trailWeather?.let { append("\nPendant la pose : ${it.summary}") }
                    },
                    "Fermer" to actions.onCloseSession,
                )
                state.openedTrail != null -> InfoBar(
                    "Piste posée le ${formatSessionDate(state.openedTrail.startedAt)}" +
                        (state.openedTrail.weather?.let { "\nMétéo pendant la pose : ${it.summary}" } ?: ""),
                    "Lancer la recherche" to { actions.onStartSearchOnTrail(state.openedTrail) },
                    "Fermer" to actions.onCloseSession,
                )
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = actions.onLayTrail, enabled = !state.busy,
                            modifier = Modifier.weight(1f).testTag("lay"),
                        ) { Text("Poser une piste") }
                        Button(
                            onClick = actions.onStartRecording, enabled = !state.busy,
                            modifier = Modifier.weight(1f).testTag("record"),
                        ) { Text("Démarrer une recherche") }
                    }
                    if (state.connected && showPeriod) {
                        RangePicker(state.range, actions.onRangeChange)
                        OutlinedButton(
                            onClick = actions.onLoad, enabled = !state.busy && state.range.isValid,
                            modifier = Modifier.fillMaxWidth().testTag("load"),
                        ) {
                            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Afficher la trace du collier")
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TrackMap(
                state.segments, Modifier.fillMaxSize(), trail = state.trailLine,
                notes = state.notes + state.trailNotes,
                onNoteClick = { note -> if (state.trailNotes.any { it.id == note.id }) trailNoteId = note.id else editedNoteId = note.id },
            )
            val idle = !state.demo && state.recording == null && state.openedSession == null && state.openedTrail == null && state.segments.isEmpty()
            if (state.tractive == null && state.dogs.isEmpty() && idle) Welcome(actions, Modifier.align(Alignment.Center))
            state.message?.let { msg ->
                Surface(
                    onClick = actions.onDismissMessage,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                ) { Text(msg, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.inverseOnSurface) }
            }
        }
        val deviation = remember(state.trailLine, state.segments) { TrailDeviation.of(state.trailLine, state.segments.flatten()) }
        when {
            state.stats != null && state.stats.pointCount > 0 -> StatsPanel(state.stats, deviation, state.notes.size) { showNotesList = true }
            state.openedTrail != null -> TrailPanel(state.openedTrail)
        }
    }
    if (askOutcome) {
        OutcomeDialog(onFinish = { askOutcome = false; actions.onStopRecording(it) }, onDismiss = { askOutcome = false })
    }
    if (showAllNotes) {
        val laying = state.recording?.kind == RecordingKind.TRAIL
        AllNotesDialog(
            onPick = { type, comment ->
                actions.onAddNote(type, comment)
                showAllNotes = false
                if (laying && type.isWarning) detailFor = type
            },
            onDismiss = { showAllNotes = false },
            categories = if (laying) LAYING_CATEGORIES else SEARCH_CATEGORIES,
        )
    }
    detailFor?.let { type ->
        WarningDetailDialog(type, onSave = { actions.onCommentLastNote(it); detailFor = null }, onDismiss = { detailFor = null })
    }
    state.trailNotes.find { it.id == trailNoteId }?.let { note ->
        val layingStart = state.openedTrail?.startedAt ?: state.trails.find { it.id == state.openedSession?.trailId }?.startedAt
        TrailNoteDialog(note, layingStart, onDismiss = { trailNoteId = null })
    }
    if (showNotesList) {
        NotesListDialog(state.notes, notesStart, onSelect = { showNotesList = false; editedNoteId = it.id }, onDismiss = { showNotesList = false })
    }
    state.notes.find { it.id == editedNoteId }?.let { note ->
        NoteEditDialog(
            note, notesStart,
            onSave = { actions.onUpdateNote(it); editedNoteId = null },
            onDelete = { actions.onDeleteNote(it); editedNoteId = null },
            onDismiss = { editedNoteId = null },
        )
    }
}

@Composable
private fun InfoBar(text: String, vararg actions: Pair<String, () -> Unit>) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp)) {
            Text(text, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.align(Alignment.End)) {
                actions.forEach { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
            }
        }
    }
}

@Composable
private fun RecordingBanner(state: UiState, onStop: () -> Unit) {
    val recording = state.recording ?: return
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    LaunchedEffect(recording.id) {
        while (true) {
            now = Instant.now().epochSecond
            delay(1_000)
        }
    }
    val laying = recording.kind == RecordingKind.TRAIL
    val elapsed = formatDuration((now - recording.startedAt).coerceAtLeast(0))
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("recording")) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (laying) "Pose de la piste · $elapsed" else "Recherche de ${recording.dogName} · $elapsed",
                style = MaterialTheme.typography.titleMedium,
            )
            recording.spaceId?.let { Text("Pour l'équipe « ${state.cloud.spaceName(it)} »", style = MaterialTheme.typography.bodySmall) }
            Text(
                when {
                    laying -> "Parcours du traceur au GPS du téléphone · ${state.recordingPoints} positions"
                    recording.source == TrackSource.PHONE -> "GPS du téléphone · ${state.recordingPoints} positions"
                    else -> "Collier Tractive (mode LIVE) · trace récupérée à l'arrivée"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!laying) {
                val trail = recording.trailId?.let { id -> state.trails.find { it.id == id } }
                Text(
                    trail?.let { "Sur la piste posée il y a ${formatDuration((recording.startedAt - it.endedAt).coerceAtLeast(0))} (affichée à l'arrivée)" }
                        ?: "Sans piste enregistrée",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Dangers et points d'attention signalés par le traceur : annoncés, sans montrer le tracé.
                trail?.warnings?.takeIf { it.isNotEmpty() }?.let { TrailWarnings(it, trail.startedAt) }
            }
            Button(onClick = onStop, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (laying) "Terminer la pose" else "Terminer la recherche")
            }
        }
    }
}

/** Première recherche avec un collier connecté : on propose le collier, le choix est gardé. */
@Composable
fun SourceChoiceDialog(onChoose: (TrackSource) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trace du chien") },
        text = {
            Text(
                "Le collier Tractive du chien donne la trace la plus fidèle : le mode LIVE sera activé pendant la recherche. " +
                    "Sinon, la trace est prise par le GPS de ce téléphone, porté par le maître-chien.\n\n" +
                    "Ce choix sera gardé pour les prochaines recherches ; il se change dans les paramètres.",
            )
        },
        confirmButton = { Button(onClick = { onChoose(TrackSource.TRACTIVE) }) { Text("Collier Tractive") } },
        dismissButton = { TextButton(onClick = { onChoose(TrackSource.PHONE) }) { Text("GPS du téléphone") } },
    )
}

/** Au départ d'une recherche : quelle piste posée le chien va-t-il suivre ? */
@Composable
fun TrailChoiceDialog(trails: List<LaidTrail>, now: Long, onChoose: (LaidTrail?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quelle piste ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("La piste ne sera affichée qu'à l'arrivée du chien.", style = MaterialTheme.typography.bodySmall)
                trails.forEach { trail ->
                    OutlinedButton(onClick = { onChoose(trail) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("Posée il y a ${formatDuration((now - trail.endedAt).coerceAtLeast(0))}")
                            Text(
                                "%.2f km · %s".format(Locale.FRENCH, trailLength(trail) / 1000, formatSessionDate(trail.startedAt)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (trail.warnings.isNotEmpty()) {
                                val n = trail.warnings.size
                                Text("⚠ $n signalement${if (n > 1) "s" else ""} du traceur", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(null) }) { Text("Sans piste") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Longueur de la piste ; gardée à part quand le parcours n'est plus qu'en ligne. */
fun trailLength(trail: LaidTrail) = trail.lengthMeters ?: trail.points.zipWithNext { a, b -> distanceMeters(a, b) }.sum()

@Composable
private fun TrailPanel(trail: LaidTrail) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().testTag("trail-stats")) {
        Row(Modifier.padding(12.dp)) {
            Stat("Longueur", "%.2f km".format(Locale.FRENCH, trailLength(trail) / 1000), Modifier.weight(1f))
            Stat("Pose", formatDuration(trail.endedAt - trail.startedAt), Modifier.weight(1f))
            Stat("Points", "${trail.points.size}", Modifier.weight(1f))
        }
    }
}

fun sourceLabel(source: TrackSource) = when (source) {
    TrackSource.TRACTIVE -> "collier Tractive"
    TrackSource.PHONE -> "GPS du téléphone"
}

private val SESSION_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM 'à' HH:mm", Locale.FRENCH)

fun formatSessionDate(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(epochSeconds).atZone(zone).format(SESSION_DATE_FORMAT)

@Composable
private fun Welcome(actions: TrackActions, modifier: Modifier) {
    Card(modifier.padding(24.dp).testTag("welcome")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bienvenue !", style = MaterialTheme.typography.titleLarge)
            Text(
                "Le traceur enregistre son parcours avec « Poser une piste ». « Démarrer une recherche » enregistre " +
                    "ensuite la trace du chien avec le GPS du téléphone, ou avec son collier si vous connectez votre compte Tractive. " +
                    "Ajoutez vos chiens pour que chacun garde ses recherches.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = actions.onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Ajouter mes chiens")
            }
            OutlinedButton(onClick = actions.onStartDemo, modifier = Modifier.fillMaxWidth()) { Text("Voir une séance de démonstration") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePicker(range: TimeRange, onChange: (TimeRange) -> Unit) {
    var dialog by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { dialog = "date" }, modifier = Modifier.weight(1.4f)) { Text(range.date.format(DATE_FORMAT)) }
        OutlinedButton(onClick = { dialog = "start" }, modifier = Modifier.weight(1f).testTag("start")) { Text(range.start.format(TIME_FORMAT)) }
        Text("→")
        OutlinedButton(onClick = { dialog = "end" }, modifier = Modifier.weight(1f).testTag("end")) { Text(range.end.format(TIME_FORMAT)) }
    }
    if (!range.isValid) Text("L'heure de fin doit être après le début.", color = MaterialTheme.colorScheme.error)

    when (dialog) {
        "date" -> {
            val initial = range.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            val today = LocalDate.now()
            val picker = rememberDatePickerState(
                initialSelectedDateMillis = initial,
                selectableDates = object : androidx.compose.material3.SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) =
                        !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
                },
            )
            DatePickerDialog(
                onDismissRequest = { dialog = null },
                confirmButton = {
                    TextButton(onClick = {
                        picker.selectedDateMillis?.let {
                            onChange(range.copy(date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()))
                        }
                        dialog = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annuler") } },
            ) { DatePicker(picker) }
        }
        "start", "end" -> {
            val isStart = dialog == "start"
            val current = if (isStart) range.start else range.end
            val picker = rememberTimePickerState(current.hour, current.minute, is24Hour = true)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(if (isStart) "Début" else "Fin") },
                text = { TimePicker(picker) },
                confirmButton = {
                    TextButton(onClick = {
                        val t = LocalTime.of(picker.hour, picker.minute)
                        onChange(if (isStart) range.copy(start = t) else range.copy(end = t))
                        dialog = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annuler") } },
            )
        }
    }
}

@Composable
private fun StatsPanel(stats: TrackStats, deviation: TrailDeviation?, notesCount: Int = 0, onShowNotes: () -> Unit = {}) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().testTag("stats")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Stat("Distance", "%.2f km".format(Locale.FRENCH, stats.distanceMeters / 1000), Modifier.weight(1f))
                Stat("Durée", formatDuration(stats.durationSeconds), Modifier.weight(1f))
                Stat("Points", "${stats.pointCount}", Modifier.weight(1f))
            }
            if (deviation != null) {
                // Comparaison avec la piste du traceur : c'est ce qui intéresse le maître-chien.
                Row(Modifier.fillMaxWidth().testTag("deviation")) {
                    Stat("Écart moyen", "%.0f m".format(deviation.meanMeters), Modifier.weight(1f))
                    Stat("Écart max", "%.0f m".format(deviation.maxMeters), Modifier.weight(1f))
                    Stat("Sur la piste", "%.0f %%".format(deviation.onTrailShare * 100), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Stat("Piste couverte", "%.0f %%".format(deviation.coverage * 100), Modifier.weight(1f))
                    Stat("Arrivée", if (deviation.reachedEnd) "Atteinte" else "Pas atteinte", Modifier.weight(1f))
                    NotesButton(notesCount, onShowNotes, Modifier.weight(1f))
                }
                return@Column
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("1 point toutes les", stats.medianIntervalSeconds?.let { formatDuration(it) } ?: "–", Modifier.weight(1f))
                Stat("Précision", stats.medianUncertaintyMeters?.let { "± %.0f m".format(it) } ?: "–", Modifier.weight(1f))
                Stat("Par GPS", "%.0f %%".format(stats.gpsShare * 100), Modifier.weight(1f))
            }
            if ((stats.medianIntervalSeconds ?: 0) > 20) {
                Text(
                    "Points trop espacés pour du mantrailing : active le mode LIVE avant la recherche.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (notesCount > 0) NotesButton(notesCount, onShowNotes, Modifier)
        }
    }
}

/** Accès aux notes de la recherche (aussi touchables sur la carte). */
@Composable
private fun NotesButton(count: Int, onClick: () -> Unit, modifier: Modifier) {
    if (count == 0) {
        Spacer(modifier)
        return
    }
    TextButton(onClick = onClick, modifier = modifier.testTag("notes-button")) {
        Text("$count note${if (count > 1) "s" else ""}", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min" + (seconds % 60).let { if (it > 0) " ${it}s" else "" }
    else -> "${seconds / 3600} h ${"%02d".format((seconds % 3600) / 60)}"
}
