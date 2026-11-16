package app.mantrailing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mantrailing.UiState
import app.mantrailing.tractive.Pet

@Composable
fun LoginScreen(state: UiState, onLogin: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val canSubmit = email.isNotBlank() && password.isNotEmpty() && !state.busy

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Mantrailing", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Connecte ton compte Tractive pour récupérer la trace GPS du collier. " +
                "Le mot de passe n'est pas conservé : seul un jeton de connexion reste sur le téléphone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email Tractive") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("email"),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Mot de passe") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("password"),
        )
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { onLogin(email, password) }, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Se connecter")
        }
    }
}

@Composable
fun PetPickerScreen(state: UiState, onPick: (Pet) -> Unit, onRetry: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Quel chien ?", style = MaterialTheme.typography.headlineMedium)
        when {
            state.busy -> CircularProgressIndicator()
            state.pets.isEmpty() -> {
                Text(state.message ?: "Aucun animal trouvé sur ce compte Tractive.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) { Text("Réessayer") }
                    TextButton(onClick = onLogout) { Text("Se déconnecter") }
                }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.pets, key = { it.id }) { pet ->
                    Card(onClick = { onPick(pet) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(pet.name, style = MaterialTheme.typography.titleMedium)
                            Text(pet.trackerId?.let { "Collier $it" } ?: "Pas de collier associé", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
