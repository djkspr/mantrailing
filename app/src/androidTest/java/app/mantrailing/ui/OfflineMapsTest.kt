package app.mantrailing.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mantrailing.UiState
import app.mantrailing.offline.Area
import app.mantrailing.offline.OfflineDownload
import app.mantrailing.offline.OfflineZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cartes hors ligne : écrans seulement (aucun téléchargement réel, pas de réseau). */
@RunWith(AndroidJUnit4::class)
class OfflineMapsTest {
    @get:Rule
    val rule = createComposeRule()

    private val forest = Area(48.415, 2.625, 48.433, 2.652)
    private val zones = listOf(
        OfflineZone(1, "Zone du mar. 17 nov. à 10:00", 1_794_906_000, 32_000_000, true, forest),
        OfflineZone(2, "Zone du lun. 16 nov. à 09:00", 1_794_819_600, 12_000_000, false, forest),
    )

    @Test
    fun parametres_zones_telechargees_et_suppression() {
        val deleted = mutableListOf<OfflineZone>()
        rule.setContent {
            MaterialTheme { SettingsScreen(UiState(ready = true, offlineZones = zones), SettingsActions(onDeleteOfflineZone = { deleted += it })) }
        }
        rule.onNodeWithText("2 zones · 44 Mo").assertIsDisplayed()
        rule.onNodeWithTag("settings-offline").performClick()
        rule.onNodeWithText("Cartes hors ligne").assertIsDisplayed()
        rule.onNodeWithText("32 Mo").assertIsDisplayed()
        rule.onNodeWithText("incomplète", substring = true).assertIsDisplayed()

        rule.onNodeWithTag("offline-zone-2").assertIsDisplayed()
        // Supprimer demande confirmation.
        rule.onAllNodesWithText("Supprimer")[0].performClick()
        rule.onNodeWithText("Supprimer « Zone du mar. 17 nov. à 10:00 » ?").assertIsDisplayed()
        rule.onNodeWithTag("offline-delete-confirm").performClick()
        assertEquals(listOf(zones[0]), deleted)
    }

    @Test
    fun parametres_sans_zone() {
        rule.setContent { MaterialTheme { SettingsScreen(UiState(ready = true), SettingsActions(), SettingsPage.OFFLINE) } }
        rule.onNodeWithTag("offline-empty").assertIsDisplayed()
        rule.onNodeWithText("« Télécharger cette zone »", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Retour").performClick()
        rule.onNodeWithText("Aucune zone : la carte demande du réseau").assertIsDisplayed()
    }

    @Test
    fun confirmation_avec_estimation_puis_telechargement() {
        val confirmed = mutableListOf<Area>()
        rule.setContent { MaterialTheme { OfflineDownloadDialog(forest, onConfirm = { confirmed += it }, onDismiss = {}) } }
        rule.onNodeWithTag("offline-estimate").assertTextContains("tuiles", substring = true).assertTextContains("Mo", substring = true)
        rule.onNodeWithTag("offline-confirm").performClick()
        assertEquals(listOf(forest), confirmed)
    }

    @Test
    fun carte_trop_dezoomee_seulement_3_km_autour_du_centre() {
        rule.setContent { MaterialTheme { OfflineDownloadDialog(Area(42.0, -5.0, 51.0, 8.0), onConfirm = {}, onDismiss = {}) } }
        rule.onNodeWithText("seuls les 3 km autour du centre", substring = true).assertIsDisplayed()
    }

    @Test
    fun progression_sur_l_accueil_et_annulation() {
        var cancels = 0
        val state = UiState(ready = true, offlineDownload = OfflineDownload("Zone du mar. 17 nov.", 42, 1_200))
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions(onCancelDownload = { cancels++ })) } }
        rule.onNodeWithText("Carte hors ligne · 42 %").assertIsDisplayed()
        rule.onNodeWithTag("offline-progress").assertIsDisplayed()
        rule.onNodeWithText("Annuler").performClick()
        assertEquals(1, cancels)
        // Pendant un téléchargement, le menu n'en propose pas un second.
        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Télécharger cette zone").assertIsNotEnabled()
    }
}
