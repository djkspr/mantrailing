package app.mantrailing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mantrailing.session.MAX_QUICK_NOTES
import app.mantrailing.session.NoteCategory
import app.mantrailing.session.NoteType
import app.mantrailing.session.SearchNote

/** Pastille de la couleur de la famille de note (la même que sur la carte). */
@Composable
private fun CategoryDot(category: NoteCategory) {
    Box(Modifier.size(12.dp).background(Color(android.graphics.Color.parseColor(category.color)), CircleShape))
}

/** Moment de la note, compté depuis le départ du chien. */
fun noteOffset(note: SearchNote, startedAt: Long) = "à ${formatDuration((note.time - startedAt).coerceAtLeast(0))}"

/**
 * Notes rapides pendant la recherche : gros boutons faciles à toucher d'une main, vibration de confirmation,
 * et annulation de la dernière note en cas d'appui par erreur.
 */
@Composable
fun QuickNotesBar(
    quick: List<NoteType>,
    notes: List<SearchNote>,
    startedAt: Long,
    onAdd: (NoteType) -> Unit,
    onOther: () -> Unit,
    onUndo: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().testTag("quick-notes"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (quick.map<NoteType, NoteType?> { it } + null).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { type ->
                    FilledTonalButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (type == null) onOther() else onAdd(type)
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    ) { Text(type?.label ?: "Autre…", textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        notes.lastOrNull()?.let { last ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${notes.size} note${if (notes.size > 1) "s" else ""} · dernière : ${last.label} (${noteOffset(last, startedAt)})",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onUndo) { Text("Annuler") }
            }
        }
    }
}

/** Toutes les notes, par famille, plus une note libre. */
@Composable
fun AllNotesDialog(onPick: (NoteType, String?) -> Unit, onDismiss: () -> Unit) {
    var free by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter une note") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NoteCategory.entries.filter { it != NoteCategory.FREE }.forEach { category ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryDot(category)
                        Text(category.label, style = MaterialTheme.typography.titleSmall)
                    }
                    // Pastilles côte à côte : toute la liste tient presque sur un écran, sans défiler sur le terrain.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NoteType.entries.filter { it.category == category }.forEach { type ->
                            OutlinedButton(
                                onClick = { onPick(type, null) },
                                modifier = Modifier.heightIn(min = 36.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) { Text(type.label) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryDot(NoteCategory.FREE)
                    Text(NoteCategory.FREE.label, style = MaterialTheme.typography.titleSmall)
                }
                OutlinedTextField(
                    value = free, onValueChange = { free = it },
                    placeholder = { Text("Ce qui s'est passé…") },
                    modifier = Modifier.fillMaxWidth().testTag("free-note"),
                )
                Button(onClick = { onPick(NoteType.FREE, free) }, enabled = free.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Ajouter la note libre")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

/** « Terminer la recherche » : comment s'est-elle terminée ? Le choix est ajouté aux notes. */
@Composable
fun OutcomeDialog(onFinish: (NoteType?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fin de la recherche") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onFinish(NoteType.FOUND) }, modifier = Modifier.fillMaxWidth()) { Text(NoteType.FOUND.label) }
                OutlinedButton(onClick = { onFinish(NoteType.WRONG_ID) }, modifier = Modifier.fillMaxWidth()) { Text(NoteType.WRONG_ID.label) }
                OutlinedButton(onClick = { onFinish(NoteType.ABANDON) }, modifier = Modifier.fillMaxWidth()) { Text(NoteType.ABANDON.label) }
                TextButton(onClick = { onFinish(null) }, modifier = Modifier.fillMaxWidth()) { Text("Terminer sans préciser") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Continuer la recherche") } },
    )
}

/** Notes d'une recherche enregistrée, dans l'ordre ; une note touchée s'ouvre pour être complétée. */
@Composable
fun NotesListDialog(notes: List<SearchNote>, startedAt: Long, onSelect: (SearchNote) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notes de la recherche") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                notes.sortedBy { it.time }.forEach { note ->
                    TextButton(onClick = { onSelect(note) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CategoryDot(note.type.category)
                            Column(Modifier.weight(1f)) {
                                Text(note.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                val details = listOfNotNull(
                                    noteOffset(note, startedAt),
                                    note.comment?.takeIf { note.type != NoteType.FREE },
                                    "pas de position".takeIf { note.lat == null },
                                ).joinToString(" · ")
                                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

/** Lire, compléter ou supprimer une note après la recherche. */
@Composable
fun NoteEditDialog(note: SearchNote, startedAt: Long, onSave: (SearchNote) -> Unit, onDelete: (SearchNote) -> Unit, onDismiss: () -> Unit) {
    var comment by rememberSaveable(note.id) { mutableStateOf(note.comment.orEmpty()) }
    var confirmDelete by rememberSaveable(note.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CategoryDot(note.type.category)
                Text(note.type.label)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${note.type.category.label} · ${noteOffset(note, startedAt)} du départ", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it },
                    label = { Text(if (note.type == NoteType.FREE) "Note" else "Commentaire") },
                    modifier = Modifier.fillMaxWidth().testTag("note-comment"),
                )
                if (confirmDelete) {
                    Button(onClick = { onDelete(note) }, modifier = Modifier.fillMaxWidth()) { Text("Supprimer définitivement") }
                } else {
                    TextButton(onClick = { confirmDelete = true }) { Text("Supprimer la note") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note.copy(comment = comment.trim().takeIf { it.isNotEmpty() })) }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Paramètres : choix des notes rapides (jusqu'à [MAX_QUICK_NOTES]), dans l'ordre de la liste. */
@Composable
fun QuickNotesSetting(selected: List<NoteType>, onChange: (List<NoteType>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Notes rapides", style = MaterialTheme.typography.titleMedium)
        Text(
            "Boutons affichés pendant la recherche (${selected.size}/$MAX_QUICK_NOTES). Les autres restent accessibles par « Autre… ».",
            style = MaterialTheme.typography.bodySmall,
        )
        NoteType.entries.filter { it != NoteType.FREE }.forEach { type ->
            val checked = type in selected
            val enabled = checked || selected.size < MAX_QUICK_NOTES
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = checked, enabled = enabled,
                    onCheckedChange = { on ->
                        // On garde l'ordre de la liste complète pour que les boutons restent à la même place.
                        val next = if (on) selected + type else selected - type
                        onChange(NoteType.entries.filter { it in next })
                    },
                    modifier = Modifier.testTag("quick-${type.name}"),
                )
                CategoryDot(type.category)
                Text(type.label, Modifier.padding(start = 8.dp), color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
