package app.mantrailing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import app.mantrailing.session.TrackSource
import app.mantrailing.track.TrackStats
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
    val onStartDemo: () -> Unit = {},
    val onExitDemo: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
    val onStartRecording: () -> Unit = {},
    val onStopRecording: () -> Unit = {},
    val onOpenSessions: () -> Unit = {},
    val onCloseSession: () -> Unit = {},
)

/** Écran d'accueil : la carte, le démarrage d'une piste, la piste en cours ou une piste enregistrée. */
@Composable
fun TrackScreen(state: UiState, actions: TrackActions) {
    var menuOpen by remember { mutableStateOf(false) }
    var showPeriod by rememberSaveable { mutableStateOf(false) }
    val title = when {
        state.demo -> "${DemoSession.DOG_NAME} (démo)"
        state.openedSession != null -> state.openedSession.dogName
        else -> state.stored?.pet?.name ?: "Mantrailing"
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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
            when {
                state.recording != null -> RecordingBanner(state, actions.onStopRecording)
                state.demo -> InfoBar("Séance de démonstration : données fictives.", "Quitter", actions.onExitDemo)
                state.openedSession != null -> InfoBar(
                    "Piste du ${formatSessionDate(state.openedSession.startedAt)} · ${sourceLabel(state.openedSession.source)}",
                    "Fermer", actions.onCloseSession,
                )
                else -> {
                    Button(
                        onClick = actions.onStartRecording, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("record"),
                    ) { Text("Démarrer une piste") }
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
            TrackMap(state.segments, Modifier.fillMaxSize())
            val idle = !state.demo && state.recording == null && state.openedSession == null && state.segments.isEmpty()
            if (!state.connected && idle) Welcome(state, actions, Modifier.align(Alignment.Center))
            state.message?.let { msg ->
                Surface(
                    onClick = actions.onDismissMessage,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                ) { Text(msg, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.inverseOnSurface) }
            }
        }
        state.stats?.takeIf { it.pointCount > 0 }?.let { StatsPanel(it) }
    }
}

@Composable
private fun InfoBar(text: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAction) { Text(action) }
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
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("recording")) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Piste en cours · ${formatDuration((now - recording.startedAt).coerceAtLeast(0))}", style = MaterialTheme.typography.titleMedium)
            Text(
                when (recording.source) {
                    TrackSource.PHONE -> "GPS du téléphone · ${state.recordingPoints} positions"
                    TrackSource.TRACTIVE -> "Collier Tractive (mode LIVE) · trace récupérée à l'arrivée"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onStop, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Terminer la piste")
            }
        }
    }
}

/** Première piste avec un collier connecté : on propose le collier, le choix est gardé. */
@Composable
fun SourceChoiceDialog(onChoose: (TrackSource) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trace du chien") },
        text = {
            Text(
                "Le collier Tractive du chien donne la trace la plus fidèle : le mode LIVE sera activé pendant la piste. " +
                    "Sinon, la trace est prise par le GPS de ce téléphone, porté par le maître-chien.\n\n" +
                    "Ce choix sera gardé pour les prochaines pistes ; il se change dans les paramètres.",
            )
        },
        confirmButton = { Button(onClick = { onChoose(TrackSource.TRACTIVE) }) { Text("Collier Tractive") } },
        dismissButton = { TextButton(onClick = { onChoose(TrackSource.PHONE) }) { Text("GPS du téléphone") } },
    )
}

fun sourceLabel(source: TrackSource) = when (source) {
    TrackSource.TRACTIVE -> "collier Tractive"
    TrackSource.PHONE -> "GPS du téléphone"
}

private val SESSION_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM 'à' HH:mm", Locale.FRENCH)

fun formatSessionDate(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(epochSeconds).atZone(zone).format(SESSION_DATE_FORMAT)

@Composable
private fun Welcome(state: UiState, actions: TrackActions, modifier: Modifier) {
    Card(modifier.padding(24.dp).testTag("welcome")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bienvenue !", style = MaterialTheme.typography.titleLarge)
            Text(
                if (state.stored == null) {
                    "« Démarrer une piste » enregistre la trace avec le GPS du téléphone. " +
                        "Pour utiliser le collier GPS du chien, connectez votre compte Tractive."
                } else {
                    "Choisissez votre chien dans les paramètres."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = actions.onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.stored == null) "Connecter un collier Tractive" else "Paramètres")
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
private fun StatsPanel(stats: TrackStats) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().testTag("stats")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Stat("Distance", "%.2f km".format(Locale.FRENCH, stats.distanceMeters / 1000), Modifier.weight(1f))
                Stat("Durée", formatDuration(stats.durationSeconds), Modifier.weight(1f))
                Stat("Points", "${stats.pointCount}", Modifier.weight(1f))
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
        }
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
