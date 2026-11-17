package app.mantrailing.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mantrailing.UiState
import app.mantrailing.session.ActiveRecording
import app.mantrailing.session.LAYING_CATEGORIES
import app.mantrailing.session.LaidTrail
import app.mantrailing.session.NoteType
import app.mantrailing.session.RecordingKind
import app.mantrailing.session.SearchNote
import app.mantrailing.session.TrackSource
import app.mantrailing.tractive.Position
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Signalements du traceur : pendant la pose, puis annoncés au maître-chien au départ de la recherche. */
@RunWith(AndroidJUnit4::class)
class TrailerNotesScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.now().epochSecond

    @Test
    fun poseBoutonsDuTraceurEtPrecisionDuDanger() {
        val added = mutableListOf<NoteType>()
        val comments = mutableListOf<String>()
        val laying = ActiveRecording("r1", "Rex", TrackSource.PHONE, now - 60, RecordingKind.TRAIL)
        rule.setContent {
            MaterialTheme {
                TrackScreen(
                    UiState(ready = true, recording = laying),
                    TrackActions(onAddNote = { type, _ -> added += type }, onCommentLastNote = { comments += it }),
                )
            }
        }

        rule.onNodeWithText("Pose de la piste", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Point d'attention").assertIsDisplayed()
        rule.onNodeWithText("Arrêt du traceur").assertIsDisplayed()
        rule.onNodeWithText("Hésitation").assertDoesNotExist() // notes du maître-chien : pas pendant la pose

        rule.onNodeWithText("Traversée de route").performClick()
        rule.onNodeWithText("Danger").performClick()
        rule.onNodeWithText("Danger noté").assertIsDisplayed()
        rule.onNodeWithText("Enregistrer").assertIsNotEnabled()
        rule.onNodeWithTag("warning-detail").performTextInput("Chien au portail")
        rule.onNodeWithText("Enregistrer").performClick()

        assertEquals(listOf(NoteType.ROAD, NoteType.DANGER), added)
        assertEquals(listOf("Chien au portail"), comments)
    }

    @Test
    fun rechercheLeMaitreChienEstPrevenu() {
        val trail = LaidTrail(
            "t1", now - 3_600, now - 3_000, listOf(Position(now - 3_600, 48.0, 2.0), Position(now - 3_000, 48.01, 2.0)),
            notes = listOf(
                SearchNote("n1", now - 3_420, NoteType.DANGER, "Route passante"),
                SearchNote("n2", now - 3_300, NoteType.SURFACE),
                SearchNote("n3", now - 3_120, NoteType.ATTENTION),
            ),
        )
        val search = ActiveRecording("r2", "Rex", TrackSource.PHONE, now - 30, RecordingKind.SEARCH, trailId = "t1")
        rule.setContent { MaterialTheme { TrackScreen(UiState(ready = true, recording = search, trails = listOf(trail)), TrackActions()) } }

        rule.onNodeWithTag("trail-warnings").assertIsDisplayed()
        rule.onNodeWithText("• Danger — Route passante (à 3 min de pose)").assertIsDisplayed()
        rule.onNodeWithText("• Point d'attention (à 8 min de pose)").assertIsDisplayed()
        rule.onNodeWithText("Changement de surface", substring = true).assertDoesNotExist()
    }

    @Test
    fun choixDeLaPisteIndiqueLesSignalements() {
        val trail = LaidTrail(
            "t1", now - 3_600, now - 3_000, listOf(Position(now - 3_600, 48.0, 2.0), Position(now - 3_000, 48.01, 2.0)),
            notes = listOf(SearchNote("n1", now - 3_420, NoteType.DANGER), SearchNote("n2", now - 3_120, NoteType.ATTENTION)),
        )
        rule.setContent { MaterialTheme { TrailChoiceDialog(listOf(trail), now, onChoose = {}, onDismiss = {}) } }
        rule.onNodeWithText("⚠ 2 signalements du traceur").assertIsDisplayed()
    }

    @Test
    fun autreNoteDuTraceur() {
        val picked = mutableListOf<NoteType>()
        rule.setContent {
            MaterialTheme { AllNotesDialog(onPick = { type, _ -> picked += type }, onDismiss = {}, categories = LAYING_CATEGORIES) }
        }
        rule.onNodeWithText("Le traceur").assertIsDisplayed()
        rule.onNodeWithText("Hésitation").assertDoesNotExist()
        rule.onNodeWithText("Obstacle").performScrollTo().performClick()
        assertEquals(listOf(NoteType.OBSTACLE), picked)
    }
}
