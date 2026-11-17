package app.mantrailing.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mantrailing.UiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Écran d'aide : rubriques dépliables, retour, tour guidé, accès depuis l'accueil et les paramètres. */
@RunWith(AndroidJUnit4::class)
class HelpScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun rubriques_qui_s_ouvrent_et_se_ferment() {
        rule.setContent { MaterialTheme { HelpScreen(onBack = {}) } }

        // Fermées au départ : seul le titre est visible.
        rule.onNodeWithText("part de la trace du chien à moins de 15 m", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("help-result").performScrollTo().performClick()
        rule.onNodeWithText("part de la trace du chien à moins de 15 m", substring = true).assertIsDisplayed()
        // Une seule rubrique ouverte à la fois.
        rule.onNodeWithTag("help-gpx").performScrollTo().performClick()
        rule.onNodeWithText("part de la trace du chien à moins de 15 m", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Garmin Explore", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("help-gpx").performScrollTo().performClick()
        rule.onNodeWithText("Garmin Explore", substring = true).assertDoesNotExist()
    }

    @Test
    fun retour_tour_guide_et_confidentialite() {
        var back = 0
        var replays = 0
        rule.setContent { MaterialTheme { HelpScreen(onBack = { back++ }, onReplayTour = { replays++ }) } }

        rule.onNodeWithTag("help-replay-tour").performScrollTo().performClick()
        rule.onNodeWithText("Politique de confidentialité").performScrollTo().assertIsDisplayed()
        // En-tête fixe : le retour reste accessible après défilement.
        rule.onNodeWithContentDescription("Retour").assertIsDisplayed().performClick()

        assertEquals(1, replays)
        assertEquals(1, back)
    }

    @Test
    fun aide_depuis_le_menu_de_l_accueil() {
        var helps = 0
        rule.setContent { MaterialTheme { TrackScreen(UiState(ready = true), TrackActions(onOpenHelp = { helps++ })) } }
        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Aide").performClick()
        assertEquals(1, helps)
    }

    @Test
    fun aide_depuis_les_parametres() {
        var helps = 0
        rule.setContent { MaterialTheme { SettingsScreen(UiState(ready = true), SettingsActions(onOpenHelp = { helps++ })) } }
        rule.onNodeWithTag("settings-help").performScrollTo().performClick()
        assertEquals(1, helps)
    }
}
