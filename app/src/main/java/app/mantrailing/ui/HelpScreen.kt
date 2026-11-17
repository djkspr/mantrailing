package app.mantrailing.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Une rubrique de l'aide : titre, et paragraphes affichés quand on l'ouvre. */
data class HelpSection(val key: String, val title: String, val paragraphs: List<String>)

/** Mode d'emploi de l'appli, tel qu'elle fonctionne aujourd'hui. */
val HELP_SECTIONS = listOf(
    HelpSection(
        "start", "Premiers pas",
        listOf(
            "Ajoutez vos chiens dans Paramètres › Chiens : chacun garde ses propres recherches.",
            "Le chien coché est celui qui cherche. Il se change aussi depuis l'accueil, en touchant son nom en haut de l'écran.",
            "Pour découvrir l'appli sans rien enregistrer : menu ⋮ › Séance de démonstration.",
        ),
    ),
    HelpSection(
        "lay", "Poser une piste",
        listOf(
            "« Poser une piste » enregistre le parcours du traceur avec le GPS du téléphone, même écran éteint ; une notification reste affichée pendant l'enregistrement.",
            "La piste se dessine sur la carte au fil des pas, avec la distance parcourue. La carte suit le traceur ; si vous la déplacez, « Me localiser » reprend le suivi.",
            "En chemin, signalez ce que le maître-chien doit savoir : Danger, Point d'attention, Traversée de route, Changement de surface, Arrêt du traceur. Un danger ou un point d'attention peut être précisé d'un mot (« chien au portail »).",
            "« Terminer la pose » garde la piste : laissez-la vieillir avant de lancer la recherche.",
        ),
    ),
    HelpSection(
        "search", "Lancer une recherche",
        listOf(
            "« Démarrer une recherche » demande quelle piste le chien va suivre (posée depuis moins de 48 h), ou « Sans piste ».",
            "La piste reste masquée pendant le travail, pour ne pas influencer le maître-chien. Seuls les signalements du traceur sont annoncés, avec leur moment sur la piste.",
            "La trace du chien vient de son collier (Tractive en mode LIVE, Weenect en super live, activés automatiquement) ou du GPS du téléphone porté par le maître-chien. Le choix se règle dans Paramètres › Colliers GPS.",
        ),
    ),
    HelpSection(
        "notes", "Notes pendant la recherche",
        listOf(
            "Une note se prend d'un seul appui : hésitation, perte de piste, reprise… Elle est placée sur la trace à l'endroit où elle a été prise.",
            "Un appui par erreur ? « Annuler » retire la dernière note. « Autre… » donne accès à toutes les notes et à une note libre.",
            "Les six boutons rapides se choisissent dans Paramètres › Notes rapides.",
            "En terminant, indiquez l'issue : Trouvé, Mauvaise identification ou Abandon.",
        ),
    ),
    HelpSection(
        "result", "Lire le résultat",
        listOf(
            "Sur la carte : la piste du traceur en bleu pointillé (départ vert, arrivée rouge), la trace du chien en orange, les notes en couleur, les signalements du traceur cerclés de jaune. Touchez une note pour la corriger ou la compléter.",
            "Écart moyen : distance moyenne entre la trace du chien et la piste. Écart max : le plus grand écart.",
            "Sur la piste : part de la trace du chien à moins de 15 m de la piste. Piste couverte : part de la piste que le chien a suivie.",
            "Arrivée : atteinte si le chien est passé près de la fin de la piste.",
            "Points trop espacés : avec un collier, activez le mode LIVE, sinon la trace n'a qu'un point toutes les quelques minutes.",
        ),
    ),
    HelpSection(
        "weather", "Météo",
        listOf(
            "La météo (température, vent et sa direction, humidité, pluie) est ajoutée toute seule à la pose et au départ du chien, depuis le service Open-Meteo.",
            "Seule une position arrondie à environ 1 km est envoyée. La météo est disponible pour les séances des 3 derniers mois.",
        ),
    ),
    HelpSection(
        "export", "Exporter",
        listOf(
            "Sur une piste ou une recherche ouverte, « Exporter » propose un rapport PDF (carte, chiffres, météo, notes) ou un fichier GPX, à envoyer, imprimer ou enregistrer (Drive, e-mail…).",
        ),
    ),
    HelpSection(
        "gpx", "Importer un fichier GPX",
        listOf(
            "Avec un GPS Garmin (Alpha…) ou une autre appli : exportez la trace en fichier GPX depuis l'appareil ou Garmin Explore, puis menu ⋮ › Importer un fichier GPX.",
            "Choisissez ce que c'est : la trace d'un chien, rattachée si besoin à une piste posée dans les 48 h avant son départ, ou une piste posée.",
            "Un fichier sans heures (itinéraire dessiné sur une carte) est daté du moment de l'import.",
        ),
    ),
    HelpSection(
        "offline", "Cartes hors ligne",
        listOf(
            "En forêt, le réseau manque souvent : avant de partir, cadrez la carte sur le terrain d'entraînement, puis menu ⋮ › « Télécharger cette zone » (Wi-Fi conseillé). Le plan IGN de la zone s'affiche ensuite sans réseau.",
            "Une zone fait au plus quelques kilomètres de côté, du quartier au chemin. Carte trop dézoomée : seuls les 3 km autour du centre sont pris.",
            "Les zones se gèrent dans Paramètres › Cartes hors ligne. Les endroits regardés récemment restent aussi en mémoire, sans téléchargement.",
        ),
    ),
    HelpSection(
        "collars", "Colliers GPS",
        listOf(
            "Connectez votre compte Tractive ou Weenect dans Paramètres › Colliers GPS : les chiens et leurs colliers sont importés.",
            "Le mot de passe n'est jamais conservé, seulement un jeton de connexion. Ces services n'ont pas d'interface officielle : un changement de leur côté peut demander une mise à jour de l'appli.",
        ),
    ),
    HelpSection(
        "cloud", "Sauvegarde en ligne et équipes",
        listOf(
            "Avec votre compte Google, pistes, recherches et chiens sont sauvegardés en ligne. La synchronisation peut attendre le Wi-Fi.",
            "Seules les séances récentes restent en entier sur le téléphone (7, 30 ou 90 jours, ou tout) ; les autres sont téléchargées à l'ouverture.",
            "Créez des équipes (famille, club…) et invitez leurs membres avec un code. Au départ, choisissez pour qui la piste ou la recherche est enregistrée : une équipe ne voit que ce qui a été fait en son nom, jamais votre espace perso.",
            "Une recherche perso peut être partagée après coup depuis « Mes pistes ».",
        ),
    ),
    HelpSection(
        "updates", "Mises à jour",
        listOf("Quand une nouvelle version est disponible, l'appli la propose à l'ouverture."),
    ),
)

/** Écran « Aide » : rubriques dépliables, tour guidé à revoir, politique de confidentialité. */
@Composable
fun HelpScreen(onBack: () -> Unit, onReplayTour: () -> Unit = {}) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // En-tête fixe : le retour reste visible quand on fait défiler.
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Retour" }) {
                Text("←", style = MaterialTheme.typography.headlineSmall)
            }
            Text("Aide", style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HELP_SECTIONS.forEachIndexed { i, section ->
                if (i > 0) HorizontalDivider()
                val expanded = open == section.key
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) { open = if (expanded) null else section.key }
                        .padding(vertical = 12.dp)
                        .testTag("help-${section.key}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expanded) {
                    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        section.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            HorizontalDivider()
            OutlinedButton(onClick = onReplayTour, modifier = Modifier.padding(top = 12.dp).testTag("help-replay-tour")) {
                Text("Revoir le tour guidé")
            }
            TextButton(onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }) { Text("Politique de confidentialité") }
        }
    }
}
