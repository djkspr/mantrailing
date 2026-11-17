package app.mantrailing.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mantrailing.UiState
import app.mantrailing.session.ActiveRecording
import app.mantrailing.session.TrackSource
import app.mantrailing.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Bandeau de mise à jour sur l'accueil. */
@RunWith(AndroidJUnit4::class)
class UpdateBannerTest {
    @get:Rule
    val rule = createComposeRule()

    private fun home(update: UpdateState, actions: UpdateActions = UpdateActions(), state: UiState = UiState(ready = true)) =
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions(), update, actions) } }

    @Test
    fun aJourPasDeBandeau() {
        home(UpdateState.None)
        rule.onNodeWithTag("update-banner").assertDoesNotExist()
    }

    @Test
    fun nouvelleVersionMettreAJourOuPlusTard() {
        var updates = 0
        var later = 0
        home(UpdateState.Available(37), UpdateActions(onUpdate = { updates++ }, onLater = { later++ }))

        rule.onNodeWithText("Une nouvelle version de Mantrailing est disponible.").assertIsDisplayed()
        rule.onNodeWithText("Mettre à jour").performClick()
        rule.onNodeWithText("Plus tard").performClick()

        assertEquals(1, updates)
        assertEquals(1, later)
    }

    @Test
    fun telechargementEnCours() {
        home(UpdateState.Downloading(42))
        rule.onNodeWithText("Téléchargement de la mise à jour… 42 %").assertIsDisplayed()
    }

    @Test
    fun pretePourRedemarrer() {
        var installs = 0
        home(UpdateState.Downloaded, UpdateActions(onInstall = { installs++ }))
        rule.onNodeWithText("Mise à jour prête.").assertIsDisplayed()
        rule.onNodeWithText("Redémarrer").performClick()
        assertEquals(1, installs)
    }

    @Test
    fun jamaisDeRedemarrageEnPleineRecherche() {
        val recording = ActiveRecording("r1", "Rex", TrackSource.PHONE, Instant.now().epochSecond)
        home(UpdateState.Downloaded, state = UiState(ready = true, recording = recording))
        rule.onNodeWithText("Mise à jour prête : vous pourrez redémarrer à la fin de l'enregistrement.").assertIsDisplayed()
        rule.onNodeWithText("Redémarrer").assertDoesNotExist()
        // La recherche en cours reste en dessous, avec son bouton pour la terminer.
        rule.onNodeWithText("Terminer la recherche").assertIsDisplayed()
    }
}
