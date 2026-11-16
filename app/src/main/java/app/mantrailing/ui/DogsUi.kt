package app.mantrailing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mantrailing.session.Dog

fun dogDetail(dog: Dog) = if (dog.hasCollar) "Porte un collier Tractive" else "Sans collier : trace au GPS du téléphone"

/** Paramètres : les chiens de l'équipe, celui qui cherche est coché. */
@Composable
fun DogsSetting(
    dogs: List<Dog>,
    activeDogId: String?,
    onSelect: (Dog) -> Unit,
    onAdd: (String) -> Unit,
    onRename: (Dog, String) -> Unit,
    onDelete: (Dog) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var renamed by remember { mutableStateOf<Dog?>(null) }
    var deleted by remember { mutableStateOf<Dog?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mes chiens", style = MaterialTheme.typography.titleMedium)
        Text(
            if (dogs.isEmpty()) "Ajoute tes chiens : chacun garde ses recherches. Ceux de ton compte Tractive sont ajoutés à la connexion."
            else "Le chien coché est celui qui cherche ; il se change aussi depuis l'accueil.",
            style = MaterialTheme.typography.bodySmall,
        )
        dogs.forEach { dog ->
            Card(Modifier.fillMaxWidth().testTag("dog-${dog.id}")) {
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = dog.id == activeDogId, role = Role.RadioButton, onClick = { onSelect(dog) })
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = dog.id == activeDogId, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(dog.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(dogDetail(dog), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { renamed = dog }) { Text("Renommer") }
                    TextButton(onClick = { deleted = dog }) { Text("Retirer") }
                }
            }
        }
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.testTag("add-dog")) { Text("Ajouter un chien") }
    }

    if (adding) {
        DogNameDialog(title = "Nouveau chien", initial = "", confirm = "Ajouter", onConfirm = { onAdd(it); adding = false }, onDismiss = { adding = false })
    }
    renamed?.let { dog ->
        DogNameDialog(title = "Renommer", initial = dog.name, confirm = "Enregistrer", onConfirm = { onRename(dog, it); renamed = null }, onDismiss = { renamed = null })
    }
    deleted?.let { dog ->
        AlertDialog(
            onDismissRequest = { deleted = null },
            title = { Text("Retirer ${dog.name} ?") },
            text = {
                Text(
                    "Ses recherches restent dans « Mes pistes »." +
                        if (dog.tractivePetId != null) " Il ne sera pas réimporté depuis Tractive ; tu pourras le rajouter à la main." else "",
                )
            },
            confirmButton = { TextButton(onClick = { onDelete(dog); deleted = null }) { Text("Retirer") } },
            dismissButton = { TextButton(onClick = { deleted = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun DogNameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    // Curseur en fin de nom, pour compléter ou corriger le nom actuel.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val name = field.text
    // Clavier ouvert d'emblée : on tape le nom directement.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field, onValueChange = { if (it.text.length <= 40) field = it },
                label = { Text("Nom du chien") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("dog-name"),
            )
        },
        confirmButton = { Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Accueil : le nom du chien qui cherche, touché pour en choisir un autre. */
@Composable
fun DogPicker(dogs: List<Dog>, active: Dog?, onSelect: (Dog) -> Unit, onManage: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("dog-picker")) {
            Text(
                "${active?.name ?: "Choisir un chien"} ▾",
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            dogs.forEach { dog ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(dog.name, style = MaterialTheme.typography.bodyLarge)
                            Text(dogDetail(dog), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    leadingIcon = { RadioButton(selected = dog.id == active?.id, onClick = null) },
                    onClick = { open = false; onSelect(dog) },
                )
            }
            DropdownMenuItem(text = { Text("Gérer les chiens…") }, onClick = { open = false; onManage() })
        }
    }
}
