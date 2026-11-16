package app.mantrailing.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mantrailing.TimeRange
import app.mantrailing.UiState
import app.mantrailing.track.TrackStats
import app.mantrailing.tractive.Pet
import app.mantrailing.tractive.Position
import org.junit.Assert.assertEquals
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

    @Test
    fun connexion_activee_une_fois_les_champs_remplis() {
        var sent: Pair<String, String>? = null
        rule.setContent { MaterialTheme { LoginScreen(UiState(ready = true)) { e, p -> sent = e to p } } }

        rule.onNodeWithText("Se connecter").assertIsNotEnabled()
        rule.onNodeWithTag("email").performTextInput("moi@example.com")
        rule.onNodeWithText("Se connecter").assertIsNotEnabled()
        rule.onNodeWithTag("password").performTextInput("secret")
        rule.onNodeWithText("Se connecter").assertIsEnabled().performClick()

        assertEquals("moi@example.com" to "secret", sent)
    }

    @Test
    fun connexion_affiche_le_message_d_erreur() {
        rule.setContent { MaterialTheme { LoginScreen(UiState(ready = true, message = "Email ou mot de passe incorrect.")) { _, _ -> } } }
        rule.onNodeWithText("Email ou mot de passe incorrect.").assertIsDisplayed()
    }

    @Test
    fun choix_du_chien() {
        var picked: Pet? = null
        val pets = listOf(pet, Pet("p2", "Mirza", null))
        rule.setContent { MaterialTheme { PetPickerScreen(UiState(ready = true, pets = pets), { picked = it }, {}, {}) } }

        rule.onNodeWithText("Pas de collier associé").assertIsDisplayed()
        rule.onNodeWithText("Rex").performClick()

        assertEquals(pet, picked)
    }

    @Test
    fun trace_affiche_le_bilan_et_alerte_sans_mode_live() {
        val segments = listOf(
            listOf(
                Position(0, 44.000, 2.0, uncertainty = 2.0, sensor = "GPS"),
                Position(150, 44.001, 2.0, uncertainty = 2.0, sensor = "GPS"),
                Position(450, 44.002, 2.0, uncertainty = 2.0, sensor = "GPS"),
            ),
        )
        val state = UiState(ready = true, segments = segments, stats = TrackStats.of(segments), loadedOnce = true)
        rule.setContent { MaterialTheme { TrackScreen(state, pet, {}, {}, {}, {}, {}, {}) } }

        rule.onNodeWithText("Rex").assertIsDisplayed()
        rule.onNodeWithText("7 min 30s").assertIsDisplayed() // durée
        rule.onNodeWithText("5 min").assertIsDisplayed() // intervalle médian
        rule.onNodeWithText("0,22 km").assertIsDisplayed()
        rule.onNodeWithText("± 2 m").assertIsDisplayed()
        rule.onNodeWithText("Points trop espacés", substring = true).assertIsDisplayed()
    }

    @Test
    fun trace_en_mode_live_sans_alerte() {
        val segments = listOf((0L..10L).map { Position(it * 3, 44.0 + it * 0.0001, 2.0, uncertainty = 3.0, sensor = "GPS") })
        val state = UiState(ready = true, segments = segments, stats = TrackStats.of(segments))
        rule.setContent { MaterialTheme { TrackScreen(state, pet, {}, {}, {}, {}, {}, {}) } }

        rule.onNodeWithText("3 s").assertIsDisplayed()
        rule.onNodeWithText("Points trop espacés", substring = true).assertDoesNotExist()
    }

    @Test
    fun boutons_charger_et_live() {
        var loads = 0
        var live: Boolean? = null
        rule.setContent { MaterialTheme { TrackScreen(UiState(ready = true), pet, {}, { loads++ }, { live = it }, {}, {}, {}) } }

        rule.onNodeWithTag("load").performClick()
        rule.onNodeWithText("Activer LIVE").performClick()

        assertEquals(1, loads)
        assertEquals(true, live)
    }

    @Test
    fun periode_invalide_bloque_le_chargement() {
        val range = TimeRange(LocalDate.of(2026, 11, 16), LocalTime.of(14, 0), LocalTime.of(13, 0))
        rule.setContent { MaterialTheme { TrackScreen(UiState(ready = true, range = range), pet, {}, {}, {}, {}, {}, {}) } }

        rule.onNodeWithText("L'heure de fin doit être après le début.").assertIsDisplayed()
        rule.onNodeWithTag("load").assertIsNotEnabled()
    }

    @Test
    fun message_cliquable_pour_le_fermer() {
        var dismissed = false
        rule.setContent {
            MaterialTheme { TrackScreen(UiState(ready = true, message = "Mode LIVE arrêté."), pet, {}, {}, {}, {}, {}, { dismissed = true }) }
        }
        rule.onNodeWithText("Mode LIVE arrêté.").performClick()
        assertEquals(true, dismissed)
    }
}
