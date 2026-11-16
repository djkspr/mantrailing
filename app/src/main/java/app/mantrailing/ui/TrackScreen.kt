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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.mantrailing.TimeRange
import app.mantrailing.UiState
import app.mantrailing.track.TrackStats
import app.mantrailing.tractive.Pet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun TrackScreen(
    state: UiState,
    pet: Pet,
    onRangeChange: (TimeRange) -> Unit,
    onLoad: () -> Unit,
    onLive: (Boolean) -> Unit,
    onChangePet: () -> Unit,
    onLogout: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pet.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onLive(true) }, enabled = !state.busy) { Text("Activer LIVE") }
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Arrêter le LIVE") }, onClick = { menuOpen = false; onLive(false) })
                        DropdownMenuItem(text = { Text("Changer de chien") }, onClick = { menuOpen = false; onChangePet() })
                        DropdownMenuItem(text = { Text("Se déconnecter") }, onClick = { menuOpen = false; onLogout() })
                    }
                }
            }
            RangePicker(state.range, onRangeChange)
            Button(
                onClick = onLoad, enabled = !state.busy && state.range.isValid,
                modifier = Modifier.fillMaxWidth().testTag("load"),
            ) {
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Afficher la trace")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TrackMap(state.segments, Modifier.fillMaxSize())
            state.message?.let { msg ->
                Surface(
                    onClick = onDismissMessage,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                ) { Text(msg, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.inverseOnSurface) }
            }
        }
        state.stats?.takeIf { it.pointCount > 0 }?.let { StatsPanel(it) }
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
