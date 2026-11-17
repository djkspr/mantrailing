package app.mantrailing.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mantrailing.UiState
import app.mantrailing.session.ActiveRecording
import app.mantrailing.session.RecordingKind
import app.mantrailing.session.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuidedTourTest {
    @get:Rule
    val rule = createComposeRule()

    private fun home(state: UiState, onFinish: () -> Unit = {}) =
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions(onFinishTour = onFinish)) } }

    @Test
    fun tour_complet_jusqu_a_c_est_parti() {
        var finished = 0
        home(UiState(ready = true, showTour = true)) { finished++ }

        rule.onNodeWithText("Bienvenue dans Mantrailing").assertIsDisplayed()
        // Accueil sans chien : la carte de bienvenue habituelle est masquée pendant le tour.
        rule.onNodeWithTag("welcome").assertDoesNotExist()
        rule.onNodeWithTag("tour-next").performClick()
        rule.onNodeWithText("2 / 6").assertIsDisplayed()
        repeat(4) { rule.onNodeWithTag("tour-next").performClick() }
        rule.onNodeWithText("La carte").assertIsDisplayed()
        rule.onNodeWithTag("tour-skip").assertDoesNotExist()
        rule.onNodeWithText("C'est parti").performClick()

        assertEquals(1, finished)
    }

    @Test
    fun passer_le_tour() {
        var finished = 0
        home(UiState(ready = true, showTour = true)) { finished++ }
        rule.onNodeWithTag("tour-next").performClick()
        rule.onNodeWithTag("tour-skip").performClick()
        assertEquals(1, finished)
    }

    @Test
    fun pas_de_tour_sans_demande_ni_pendant_un_enregistrement_ou_la_demo() {
        home(UiState(ready = true))
        rule.onNodeWithTag("tour").assertDoesNotExist()
        rule.onNodeWithTag("welcome").assertIsDisplayed()
    }

    @Test
    fun pas_de_tour_pendant_la_pose() {
        val recording = ActiveRecording("t1", "Rex", TrackSource.PHONE, 0, RecordingKind.TRAIL)
        home(UiState(ready = true, showTour = true, recording = recording))
        rule.onNodeWithTag("tour").assertDoesNotExist()
    }

    @Test
    fun pas_de_tour_dans_la_demo() {
        home(UiState(ready = true, showTour = true, demo = true))
        rule.onNodeWithTag("tour").assertDoesNotExist()
    }

    @Test
    fun revoir_le_tour_depuis_a_propos() {
        var replays = 0
        rule.setContent { MaterialTheme { SettingsScreen(UiState(ready = true), SettingsActions(onReplayTour = { replays++ }), SettingsPage.ABOUT) } }
        rule.onNodeWithTag("replay-tour").performClick()
        assertEquals(1, replays)
    }
}
