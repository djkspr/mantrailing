package app.mantrailing.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.mantrailing.CloudState
import app.mantrailing.Invite
import app.mantrailing.session.KEEP_ON_PHONE_CHOICES
import app.mantrailing.sync.Team
import app.mantrailing.sync.InviteCodes

data class CloudActions(
    val onSignIn: () -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onWifiOnly: (Boolean) -> Unit = {},
    val onKeepDays: (Int?) -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onCreateTeam: (String) -> Unit = {},
    val onInvite: (Team) -> Unit = {},
    val onDismissInvite: () -> Unit = {},
    val onJoin: (String) -> Unit = {},
    val onLeave: (Team) -> Unit = {},
)

/** Paramètres : sauvegarde en ligne (compte Google), place sur le téléphone, équipes. */
@Composable
fun CloudSetting(cloud: CloudState, now: Long, actions: CloudActions) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!cloud.available) {
            Text("Pas encore disponible dans cette version.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Text(
            "Pistes, recherches et chiens sont copiés sur le serveur de Mantrailing (hébergé en France) : le téléphone " +
                "ne garde en entier que les plus récents, rien n'est perdu en changeant de téléphone, et vous pouvez " +
                "partager avec une équipe ce que vous choisissez.",
            style = MaterialTheme.typography.bodySmall,
        )
        WifiOnlySwitch(cloud.wifiOnly, actions.onWifiOnly)
        cloud.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("cloud-message")) }
        if (cloud.email == null) {
            Button(onClick = actions.onSignIn, enabled = !cloud.busy, modifier = Modifier.fillMaxWidth().testTag("cloud-sign-in")) {
                if (cloud.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Activer avec mon compte Google")
            }
            return@Column
        }
        KeepDaysChoice(cloud.keepDays, actions.onKeepDays)
        Card(Modifier.fillMaxWidth().testTag("cloud-account")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Compte : ${cloud.email}", style = MaterialTheme.typography.titleSmall)
                Text(syncStatus(cloud, now), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("cloud-status"))
                cloud.lastError?.let {
                    Text("Dernier essai échoué : $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = actions.onSyncNow, enabled = !cloud.syncing && !cloud.busy) { Text("Synchroniser maintenant") }
                    TextButton(onClick = actions.onSignOut, enabled = !cloud.busy) { Text("Désactiver") }
                }
                TextButton(onClick = { confirmDelete = true }, enabled = !cloud.busy) {
                    Text("Supprimer mes données en ligne", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer les données en ligne ?") },
            text = {
                Text(
                    "Votre espace perso en ligne et votre compte de sauvegarde sont supprimés définitivement, et vous quittez " +
                        "vos équipes (ce qui y a été partagé reste aux autres membres). Les données perso de ce téléphone restent. " +
                        "Google vous redemandera votre compte pour confirmer.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; actions.onDelete() }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun KeepDaysChoice(keepDays: Int?, onChange: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Garder en entier sur le téléphone", style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()).testTag("keep-days"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KEEP_ON_PHONE_CHOICES.forEach { days ->
                FilterChip(selected = keepDays == days, onClick = { onChange(days) }, label = { Text(days?.let { "$it jours" } ?: "Tout") })
            }
        }
        Text(
            if (keepDays == null) "Tout reste sur le téléphone."
            else "Au-delà, une piste ou une recherche n'est plus qu'en ligne : son résumé reste ici, elle est téléchargée à l'ouverture.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Équipes : famille, club, binôme… Chacune ne voit que ce qui a été fait en son nom. */
@Composable
fun TeamsSetting(cloud: CloudState, actions: CloudActions) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var joining by rememberSaveable { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<Team?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Une équipe voit uniquement les pistes, recherches et chiens faits ou partagés en son nom ; " +
                "votre espace perso et vos autres équipes lui restent invisibles.",
            style = MaterialTheme.typography.bodySmall,
        )
        cloud.teams.forEach { team ->
            Card(Modifier.fillMaxWidth().testTag("team-${team.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(team.name, style = MaterialTheme.typography.titleSmall)
                    Text("Membres : ${team.members.values.sorted().joinToString()}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { actions.onInvite(team) }, enabled = !cloud.busy) { Text("Inviter") }
                        TextButton(onClick = { leaving = team }, enabled = !cloud.busy) { Text("Quitter") }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { creating = true }, enabled = !cloud.busy, modifier = Modifier.testTag("create-team")) { Text("Créer une équipe") }
            TextButton(onClick = { joining = true }, enabled = !cloud.busy, modifier = Modifier.testTag("join-team")) { Text("Rejoindre") }
        }
    }

    cloud.invite?.let { InviteDialog(it, actions.onDismissInvite) }
    if (creating) {
        TextDialog(
            title = "Nouvelle équipe", label = "Nom (Famille, club…)", confirm = "Créer", tag = "team-name",
            capitalization = KeyboardCapitalization.Sentences, isValid = { it.isNotBlank() },
            onConfirm = { creating = false; actions.onCreateTeam(it) }, onDismiss = { creating = false },
        )
    }
    if (joining) {
        TextDialog(
            title = "Rejoindre une équipe", label = "Code reçu", confirm = "Rejoindre", tag = "join-code",
            capitalization = KeyboardCapitalization.Characters,
            help = "Vous verrez ce qui se fait dans l'équipe. Rien de ce que vous avez déjà n'y est envoyé.",
            isValid = { InviteCodes.normalize(it).length == 8 },
            onConfirm = { joining = false; actions.onJoin(it) }, onDismiss = { joining = false },
        )
    }
    leaving?.let { team ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text("Quitter « ${team.name} » ?") },
            text = {
                Text(
                    if (team.members.size <= 1) "Vous êtes le dernier membre : l'équipe et tout ce qui y a été partagé seront supprimés."
                    else "Ce qui a été partagé dans l'équipe lui reste, et est retiré de ce téléphone.",
                )
            },
            confirmButton = { TextButton(onClick = { leaving = null; actions.onLeave(team) }) { Text("Quitter") } },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun InviteDialog(invite: Invite, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val formatted = InviteCodes.format(invite.code)
    val text = "Rejoins l'équipe « ${invite.teamName} » dans l'appli Mantrailing : Paramètres › Équipes › Rejoindre, " +
        "code $formatted (valable 7 jours)."
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inviter dans « ${invite.teamName} »") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(formatted, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("invite-code"))
                Text(
                    "À saisir dans « Rejoindre » sur l'autre téléphone, valable 7 jours. La personne verra ce qui se fait " +
                        "dans cette équipe, et rien d'autre.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Envoyer le code"))
            }) { Text("Envoyer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun TextDialog(
    title: String,
    label: String,
    confirm: String,
    tag: String,
    capitalization: KeyboardCapitalization,
    isValid: (String) -> Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    help: String? = null,
) {
    var value by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = value, onValueChange = { value = it.take(40) },
                    label = { Text(label) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = capitalization),
                    modifier = Modifier.fillMaxWidth().testTag(tag),
                )
                help?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(value) }, enabled = isValid(value), modifier = Modifier.testTag("$tag-confirm")) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun WifiOnlySwitch(wifiOnly: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = wifiOnly, role = Role.Switch, onValueChange = onChange).testTag("cloud-wifi-only"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Seulement en Wi-Fi", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (wifiOnly) "Pas de données mobiles : la synchronisation attend le Wi-Fi."
                else "Synchronise dès qu'il y a du réseau, Wi-Fi ou données mobiles.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = wifiOnly, onCheckedChange = null)
    }
}

fun syncStatus(cloud: CloudState, now: Long): String = when {
    cloud.syncing -> "Synchronisation en cours…"
    cloud.lastSyncAt == null -> if (cloud.wifiOnly) "Première synchronisation dès que le Wi-Fi est là." else "Première synchronisation en attente de réseau."
    else -> "Dernière synchronisation il y a ${formatDuration(((now - cloud.lastSyncAt) / 1000).coerceAtLeast(0))}."
}

/** Où ranger une piste, une recherche ou un chien : espace perso ou une équipe. */
@Composable
fun SpaceChoiceDialog(
    title: String,
    teams: List<Team>,
    selected: String?,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
    help: String = "Seuls les membres de l'équipe choisie la verront. « Perso » : vous seul.",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(help, style = MaterialTheme.typography.bodySmall)
                (listOf<Team?>(null) + teams).forEach { team ->
                    val id = team?.id
                    val label = team?.name ?: "Perso"
                    if (id == selected) {
                        Button(onClick = { onChoose(id) }, modifier = Modifier.fillMaxWidth().testTag("space-${id ?: "perso"}")) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onChoose(id) }, modifier = Modifier.fillMaxWidth().testTag("space-${id ?: "perso"}")) { Text(label) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
