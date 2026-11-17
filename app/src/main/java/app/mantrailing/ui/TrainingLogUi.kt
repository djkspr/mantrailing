package app.mantrailing.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.mantrailing.session.Difficulty
import app.mantrailing.session.Terrain
import app.mantrailing.session.TrainingLog

/**
 * Carnet d'entraînement d'une piste ([forTrail] : ce que le traceur sait — traceur, lieu, terrain, objet odorant,
 * difficultés) ou d'une recherche (en plus : récompense et note du travail de l'équipe). Tout est facultatif.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainingLogDialog(
    initial: TrainingLog?,
    forTrail: Boolean,
    runnerSuggestions: List<String>,
    scentSuggestions: List<String>,
    placeSuggestions: List<String>,
    onSave: (TrainingLog) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = initial ?: TrainingLog()
    var runner by rememberSaveable { mutableStateOf(start.runner.orEmpty()) }
    var scent by rememberSaveable { mutableStateOf(start.scentArticle.orEmpty()) }
    var place by rememberSaveable { mutableStateOf(start.place.orEmpty()) }
    var terrain by rememberSaveable { mutableStateOf(start.terrain.map { it.name }) }
    var difficulties by rememberSaveable { mutableStateOf(start.difficulties.map { it.name }) }
    var reward by rememberSaveable { mutableStateOf(start.reward.orEmpty()) }
    var rating by rememberSaveable { mutableStateOf(start.rating ?: 0) }
    var comment by rememberSaveable { mutableStateOf(start.comment.orEmpty()) }

    fun current() = TrainingLog(
        runner, scent, place, terrain.map(Terrain::valueOf), difficulties.map(Difficulty::valueOf),
        reward.takeUnless { forTrail }, rating.takeIf { it > 0 && !forTrail }, comment,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (forTrail) "Carnet de la piste" else "Carnet de la recherche") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("training-log"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tout est facultatif ; modifiable plus tard avec « Carnet ».", style = MaterialTheme.typography.bodySmall)
                SuggestedField("Traceur", runner, { runner = it }, runnerSuggestions, "log-runner", KeyboardCapitalization.Words)
                SuggestedField("Objet odorant", scent, { scent = it }, scentSuggestions, "log-scent", KeyboardCapitalization.Sentences)
                SuggestedField("Lieu", place, { place = it }, placeSuggestions, "log-place", KeyboardCapitalization.Sentences)
                Text("Terrain", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Terrain.entries.forEach { t ->
                        FilterChip(
                            selected = t.name in terrain,
                            onClick = { terrain = if (t.name in terrain) terrain - t.name else terrain + t.name },
                            label = { Text(t.label) },
                            modifier = Modifier.testTag("log-terrain-${t.name}"),
                        )
                    }
                }
                Text("Difficultés", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Difficulty.entries.forEach { d ->
                        FilterChip(
                            selected = d.name in difficulties,
                            onClick = { difficulties = if (d.name in difficulties) difficulties - d.name else difficulties + d.name },
                            label = { Text(d.label) },
                            modifier = Modifier.testTag("log-diff-${d.name}"),
                        )
                    }
                }
                if (!forTrail) {
                    OutlinedTextField(
                        value = reward, onValueChange = { reward = it }, label = { Text("Récompense") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().testTag("log-reward"),
                    )
                    Text("Travail de l'équipe", style = MaterialTheme.typography.labelLarge)
                    Row {
                        (1..5).forEach { n ->
                            TextButton(
                                onClick = { rating = if (rating == n) 0 else n },
                                modifier = Modifier.testTag("log-star-$n").semantics { contentDescription = "$n étoile${if (n > 1) "s" else ""}" },
                            ) {
                                Text(if (n <= rating) "★" else "☆", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it }, label = { Text("Commentaire") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag("log-comment"),
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(current()) }, modifier = Modifier.testTag("log-save")) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("log-later")) { Text("Plus tard") } },
    )
}

/** Champ texte avec les valeurs déjà utilisées proposées en dessous (un appui les recopie). */
@Composable
private fun SuggestedField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    suggestions: List<String>,
    tag: String,
    capitalization: KeyboardCapitalization,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = capitalization),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
        val shown = suggestions.filter { it.contains(value.trim(), ignoreCase = true) && !it.equals(value.trim(), ignoreCase = true) }.take(6)
        if (shown.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { s -> AssistChip(onClick = { onChange(s) }, label = { Text(s) }, modifier = Modifier.testTag("$tag-suggestion")) }
            }
        }
    }
}
