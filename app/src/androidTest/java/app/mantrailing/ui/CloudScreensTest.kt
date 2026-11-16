package app.mantrailing.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mantrailing.CloudState
import app.mantrailing.Invite
import app.mantrailing.UiState
import app.mantrailing.session.ActiveRecording
import app.mantrailing.session.Dog
import app.mantrailing.session.LaidTrail
import app.mantrailing.session.SessionSummary
import app.mantrailing.session.TrackSource
import app.mantrailing.session.TrailSession
import app.mantrailing.sync.Team
import app.mantrailing.tractive.Position
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Sauvegarde en ligne, équipes et espaces : ce que chacun voit et partage. */
@RunWith(AndroidJUnit4::class)
class CloudScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val famille = Team("t-fam", "Famille", "u1", mapOf("u1" to "Moi", "u2" to "Marie"))
    private val club = Team("t-club", "Club du Gâtinais", "u3", mapOf("u1" to "Moi", "u3" to "Paul", "u4" to "Léa"))
    private val signedIn = CloudState(available = true, email = "moi@example.com", teams = listOf(famille, club), lastSyncAt = 0)

    private fun settings(cloud: CloudState, actions: CloudActions = CloudActions(), dogs: List<Dog> = emptyList(), onMoveDog: (Dog, String?) -> Unit = { _, _ -> }) =
        rule.setContent {
            MaterialTheme {
                SettingsScreen(UiState(ready = true, cloud = cloud, dogs = dogs), SettingsActions(cloud = actions, onMoveDog = onMoveDog))
            }
        }

    @Test
    fun sauvegarde_absente_de_cette_version() {
        settings(CloudState(available = false))
        rule.onNodeWithText("Pas encore disponible dans cette version.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("cloud-sign-in").assertDoesNotExist()
    }

    @Test
    fun activation_et_wifi_seulement_par_defaut() {
        var signIns = 0
        val wifi = mutableListOf<Boolean>()
        settings(CloudState(available = true), CloudActions(onSignIn = { signIns++ }, onWifiOnly = { wifi += it }))

        rule.onNodeWithTag("cloud-wifi-only").performScrollTo().assertIsOn().performClick()
        rule.onNodeWithTag("cloud-sign-in").performScrollTo().performClick()
        // Pas d'équipes ni de durée de conservation tant que la sauvegarde n'est pas active.
        rule.onNodeWithTag("keep-days").assertDoesNotExist()
        rule.onNodeWithTag("create-team").assertDoesNotExist()

        assertEquals(listOf(false), wifi)
        assertEquals(1, signIns)
    }

    @Test
    fun wifi_desactive_affiche_donnees_mobiles() {
        settings(CloudState(available = true, wifiOnly = false))
        rule.onNodeWithTag("cloud-wifi-only").performScrollTo().assertIsOff()
        rule.onNodeWithText("Synchronise dès qu'il y a du réseau", substring = true).assertIsDisplayed()
    }

    @Test
    fun compte_connecte_conservation_et_synchro() {
        val kept = mutableListOf<Int?>()
        var syncs = 0
        settings(signedIn, CloudActions(onKeepDays = { kept += it }, onSyncNow = { syncs++ }))

        rule.onNodeWithText("Compte : moi@example.com").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("30 jours").performScrollTo().performClick()
        rule.onNodeWithText("Tout").performScrollTo().performClick()
        rule.onNodeWithText("Synchroniser maintenant").performScrollTo().performClick()

        assertEquals(listOf<Int?>(30, null), kept)
        assertEquals(1, syncs)
    }

    @Test
    fun equipes_inviter_quitter_creer_rejoindre() {
        val invited = mutableListOf<Team>()
        val left = mutableListOf<Team>()
        val created = mutableListOf<String>()
        val joined = mutableListOf<String>()
        settings(
            signedIn,
            CloudActions(onInvite = { invited += it }, onLeave = { left += it }, onCreateTeam = { created += it }, onJoin = { joined += it }),
        )

        rule.onNodeWithText("Membres : Marie, Moi").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Membres : Léa, Moi, Paul").performScrollTo().assertIsDisplayed()

        rule.onNodeWithTag("team-t-club").performScrollTo()
        rule.onNodeWithTag("create-team").performScrollTo().performClick()
        rule.onNodeWithTag("team-name-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("team-name").performTextInput("Binôme du jeudi")
        rule.onNodeWithTag("team-name-confirm").performClick()

        // Rejoindre : code de 8 caractères, tirets et minuscules acceptés ; rien n'est envoyé à l'équipe.
        rule.onNodeWithTag("join-team").performScrollTo().performClick()
        rule.onNodeWithText("Rien de ce que vous avez déjà n'y est envoyé.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("join-code").performTextInput("abcd-efg")
        rule.onNodeWithTag("join-code-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("join-code").performTextInput("h")
        rule.onNodeWithTag("join-code-confirm").assertIsEnabled().performClick()

        // Inviter dans le club, quitter la famille (avec confirmation).
        rule.onNodeWithTag("team-t-club").performScrollTo()
        rule.onAllNodesWithText("Inviter")[1].performClick()
        rule.onAllNodesWithText("Quitter")[0].performClick()
        rule.onNodeWithText("Quitter « Famille » ?").assertIsDisplayed()
        rule.onNodeWithText("Ce qui a été partagé dans l'équipe lui reste", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Quitter")[2].performClick()

        assertEquals(listOf("Binôme du jeudi"), created)
        assertEquals(listOf("abcd-efgh"), joined)
        assertEquals(listOf(club), invited)
        assertEquals(listOf(famille), left)
    }

    @Test
    fun code_d_invitation_affiche() {
        settings(signedIn.copy(invite = Invite("Famille", "ABCDEFGH")))
        rule.onNodeWithTag("invite-code").assertIsDisplayed()
        rule.onNodeWithText("ABCD-EFGH").assertIsDisplayed()
        rule.onNodeWithText("Envoyer").assertIsEnabled()
    }

    @Test
    fun choix_de_l_espace_au_depart() {
        val chosen = mutableListOf<String?>()
        rule.setContent {
            MaterialTheme {
                SpaceChoiceDialog("Recherche pour…", listOf(famille, club), selected = "t-club", onChoose = { chosen += it }, onDismiss = {})
            }
        }
        rule.onNodeWithText("Seuls les membres de l'équipe choisie la verront", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("space-perso").performClick()
        rule.onNodeWithTag("space-t-club").performClick()
        assertEquals(listOf(null, "t-club"), chosen)
    }

    @Test
    fun chien_partage_avec_une_equipe() {
        val moved = mutableListOf<Pair<Dog, String?>>()
        val rex = Dog("d1", "Rex")
        settings(signedIn, dogs = listOf(rex, Dog("d2", "Nala", spaceId = "t-fam")), onMoveDog = { d, s -> moved += d to s })

        rule.onNodeWithText("Partagé avec Famille").assertIsDisplayed()
        rule.onNodeWithContentDescription("Actions pour Rex").performClick()
        rule.onNodeWithText("Partager avec…").performClick()
        rule.onNodeWithTag("space-t-fam").performClick()

        assertEquals(listOf(rex to "t-fam"), moved)
    }

    // --- Mes pistes par espace ---

    private val now = Instant.now().epochSecond
    private val perso = TrailSession("s-perso", "Rex", TrackSource.PHONE, now - 4 * 86_400, now - 4 * 86_400 + 600)
    private val enClub = TrailSession("s-club", "Rex", TrackSource.PHONE, now - 86_400, now - 86_400 + 600, spaceId = "t-club")
    private val archivee = TrailSession(
        "s-old", "Rex", TrackSource.PHONE, now - 60 * 86_400, now - 60 * 86_400 + 900,
        archived = true, summary = SessionSummary(1234.0, 300, 12.0, true),
    )
    private val trailClub = LaidTrail("t-1", now - 3600, now - 3000, listOf(Position(0, 48.0, 2.0), Position(3, 48.001, 2.0)), spaceId = "t-club")

    @Test
    fun mes_pistes_filtre_par_espace() {
        rule.setContent {
            MaterialTheme { SessionsScreen(listOf(enClub, perso), listOf(trailClub), emptyList(), SessionsActions(), teams = listOf(famille, club)) }
        }
        rule.onNodeWithTag("session-s-perso").assertIsDisplayed()
        rule.onNodeWithTag("session-s-club").assertIsDisplayed()

        rule.onNodeWithText("Club du Gâtinais").performClick()
        rule.onNodeWithTag("session-s-perso").assertDoesNotExist()
        rule.onNodeWithTag("session-s-club").assertIsDisplayed()
        rule.onNodeWithTag("trail-t-1").assertIsDisplayed()

        rule.onNodeWithText("Perso").performClick()
        rule.onNodeWithTag("session-s-club").assertDoesNotExist()
        rule.onNodeWithTag("trail-t-1").assertDoesNotExist()
        rule.onNodeWithTag("session-s-perso").assertIsDisplayed()
    }

    @Test
    fun mes_pistes_partager_une_recherche_perso() {
        val moved = mutableListOf<Pair<String, String?>>()
        rule.setContent {
            MaterialTheme {
                SessionsScreen(
                    listOf(perso), emptyList(), emptyList(),
                    SessionsActions(onMoveSession = { s, space -> moved += s.id to space }), teams = listOf(famille, club),
                )
            }
        }
        rule.onNodeWithText("Partager").performClick()
        rule.onNodeWithTag("space-t-fam").performClick()
        assertEquals(listOf("s-perso" to "t-fam"), moved)
    }

    @Test
    fun recherche_qui_n_est_plus_qu_en_ligne() {
        var opened: TrailSession? = null
        rule.setContent {
            MaterialTheme { SessionsScreen(listOf(archivee), emptyList(), emptyList(), SessionsActions(onOpen = { opened = it })) }
        }
        rule.onNodeWithText("1,23 km · 15 min · écart moyen 12 m · arrivée atteinte · en ligne").assertIsDisplayed()
        // Sans équipe : ni filtre d'espace ni bouton de partage.
        rule.onNodeWithTag("space-filter").assertDoesNotExist()
        rule.onNodeWithText("Partager").assertDoesNotExist()
        rule.onNodeWithTag("session-s-old").performClick()
        assertEquals("s-old", opened?.id)
    }

    @Test
    fun recherche_en_cours_pour_une_equipe() {
        val recording = ActiveRecording("r1", "Rex", TrackSource.PHONE, now, spaceId = "t-club")
        rule.setContent {
            MaterialTheme { TrackScreen(UiState(ready = true, recording = recording, cloud = signedIn), TrackActions()) }
        }
        rule.onNodeWithText("Pour l'équipe « Club du Gâtinais »").assertIsDisplayed()
    }
}
