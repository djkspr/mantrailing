package app.mantrailing.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mantrailing.TimeRange
import app.mantrailing.UiState
import app.mantrailing.data.StoredSession
import app.mantrailing.demo.DemoSession
import app.mantrailing.track.TrackStats
import app.mantrailing.tractive.Pet
import app.mantrailing.tractive.Position
import app.mantrailing.tractive.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val pet = Pet("p1", "Rex", "TRK1")
    private val session = Session("u1", "tok", 0)
    private val connected = UiState(ready = true, stored = StoredSession(session, pet))

    // --- Accueil sans compte (ce que voit l'examinateur Google Play) ---

    @Test
    fun accueil_sans_compte_propose_connexion_et_demo() {
        var settings = 0
        var demo = 0
        rule.setContent {
            MaterialTheme { TrackScreen(UiState(ready = true), TrackActions(onOpenSettings = { settings++ }, onStartDemo = { demo++ })) }
        }

        rule.onNodeWithText("Mantrailing").assertIsDisplayed()
        rule.onNodeWithText("Activer LIVE").assertDoesNotExist()
        rule.onNodeWithTag("load").assertDoesNotExist()
        rule.onNodeWithText("Connecter un collier Tractive").performClick()
        rule.onNodeWithText("Voir une séance de démonstration").performClick()

        assertEquals(1, settings)
        assertEquals(1, demo)
    }

    @Test
    fun menu_donne_acces_aux_parametres() {
        var settings = 0
        rule.setContent { MaterialTheme { TrackScreen(UiState(ready = true), TrackActions(onOpenSettings = { settings++ })) } }

        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Paramètres").performClick()

        assertEquals(1, settings)
    }

    @Test
    fun seance_de_demonstration_affichee_avec_bandeau() {
        var exit = 0
        val state = UiState(
            ready = true, demo = true, range = DemoSession.range,
            segments = DemoSession.segments, stats = TrackStats.of(DemoSession.segments),
        )
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions(onExitDemo = { exit++ })) } }

        rule.onNodeWithText("Rex (démo)").assertIsDisplayed()
        rule.onNodeWithText("Séance de démonstration : données fictives.").assertIsDisplayed()
        rule.onNodeWithTag("welcome").assertDoesNotExist()
        rule.onNodeWithText("Activer LIVE").assertDoesNotExist()
        rule.onNodeWithTag("stats").assertIsDisplayed()
        rule.onNodeWithText("Quitter").performClick()

        assertEquals(1, exit)
    }

    // --- Accueil connecté ---

    @Test
    fun trace_affiche_le_bilan_et_alerte_sans_mode_live() {
        val segments = listOf(
            listOf(
                Position(0, 44.000, 2.0, uncertainty = 2.0, sensor = "GPS"),
                Position(150, 44.001, 2.0, uncertainty = 2.0, sensor = "GPS"),
                Position(450, 44.002, 2.0, uncertainty = 2.0, sensor = "GPS"),
            ),
        )
        val state = connected.copy(segments = segments, stats = TrackStats.of(segments), loadedOnce = true)
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions()) } }

        rule.onNodeWithText("Rex").assertIsDisplayed()
        rule.onNodeWithTag("welcome").assertDoesNotExist()
        rule.onNodeWithText("7 min 30s").assertIsDisplayed() // durée
        rule.onNodeWithText("5 min").assertIsDisplayed() // intervalle médian
        rule.onNodeWithText("0,22 km").assertIsDisplayed()
        rule.onNodeWithText("Points trop espacés", substring = true).assertIsDisplayed()
    }

    @Test
    fun trace_en_mode_live_sans_alerte() {
        val segments = listOf((0L..10L).map { Position(it * 3, 44.0 + it * 0.0001, 2.0, uncertainty = 3.0, sensor = "GPS") })
        val state = connected.copy(segments = segments, stats = TrackStats.of(segments))
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions()) } }

        rule.onNodeWithText("3 s").assertIsDisplayed()
        rule.onNodeWithText("Points trop espacés", substring = true).assertDoesNotExist()
    }

    @Test
    fun boutons_charger_et_live() {
        var loads = 0
        var live: Boolean? = null
        rule.setContent { MaterialTheme { TrackScreen(connected, TrackActions(onLoad = { loads++ }, onLive = { live = it })) } }

        rule.onNodeWithTag("load").performClick()
        rule.onNodeWithText("Activer LIVE").performClick()

        assertEquals(1, loads)
        assertEquals(true, live)
    }

    @Test
    fun periode_invalide_bloque_le_chargement() {
        val range = TimeRange(LocalDate.of(2026, 11, 16), LocalTime.of(14, 0), LocalTime.of(13, 0))
        rule.setContent { MaterialTheme { TrackScreen(connected.copy(range = range), TrackActions()) } }

        rule.onNodeWithText("L'heure de fin doit être après le début.").assertIsDisplayed()
        rule.onNodeWithTag("load").assertIsNotEnabled()
    }

    @Test
    fun message_cliquable_pour_le_fermer() {
        var dismissed = false
        rule.setContent {
            MaterialTheme { TrackScreen(connected.copy(message = "Mode LIVE arrêté."), TrackActions(onDismissMessage = { dismissed = true })) }
        }
        rule.onNodeWithText("Mode LIVE arrêté.").performClick()
        assertTrue(dismissed)
    }

    // --- Paramètres ---

    @Test
    fun parametres_connexion_activee_une_fois_les_champs_remplis() {
        var sent: Pair<String, String>? = null
        rule.setContent { MaterialTheme { SettingsScreen(UiState(ready = true), SettingsActions(onLogin = { e, p -> sent = e to p })) } }

        rule.onNodeWithText("Politique de confidentialité").assertIsDisplayed()
        rule.onNodeWithText("Se connecter").assertIsNotEnabled()
        rule.onNodeWithTag("email").performTextInput("moi@example.com")
        rule.onNodeWithText("Se connecter").assertIsNotEnabled()
        rule.onNodeWithTag("password").performTextInput("secret")
        rule.onNodeWithText("Se connecter").assertIsEnabled().performClick()

        assertEquals("moi@example.com" to "secret", sent)
    }

    @Test
    fun parametres_affichent_le_message_d_erreur() {
        rule.setContent {
            MaterialTheme { SettingsScreen(UiState(ready = true, message = "Email ou mot de passe incorrect."), SettingsActions()) }
        }
        rule.onNodeWithText("Email ou mot de passe incorrect.").assertIsDisplayed()
    }

    @Test
    fun parametres_choix_du_chien() {
        var picked: Pet? = null
        val state = UiState(ready = true, stored = StoredSession(session, null), pets = listOf(pet, Pet("p2", "Mirza", null)))
        rule.setContent { MaterialTheme { SettingsScreen(state, SettingsActions(onPickPet = { picked = it })) } }

        rule.onNodeWithText("Pas de collier associé").assertIsDisplayed()
        rule.onNodeWithText("Rex").performClick()

        assertEquals(pet, picked)
    }

    @Test
    fun parametres_connecte_deconnexion_et_retour() {
        var logout = 0
        var back = 0
        rule.setContent { MaterialTheme { SettingsScreen(connected, SettingsActions(onLogout = { logout++ }, onBack = { back++ })) } }

        rule.onNodeWithText("Connecté : Rex").assertIsDisplayed()
        rule.onNodeWithText("Se déconnecter").performClick()
        rule.onNodeWithContentDescription("Retour").performClick()

        assertEquals(1, logout)
        assertEquals(1, back)
    }
}
