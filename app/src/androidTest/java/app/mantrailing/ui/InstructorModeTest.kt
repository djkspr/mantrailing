package app.mantrailing.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mantrailing.UiState
import app.mantrailing.session.ActiveRecording
import app.mantrailing.session.LaidTrail
import app.mantrailing.session.TrackSource
import app.mantrailing.session.TrailSession
import app.mantrailing.track.CorridorStatus
import app.mantrailing.tractive.Position
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Mode formateur : choix au départ, bandeau du couloir, rappel sur la recherche enregistrée. */
@RunWith(AndroidJUnit4::class)
class InstructorModeTest {
    @get:Rule
    val rule = createComposeRule()

    private val trail = LaidTrail(
        "t1", 1_794_830_000, 1_794_830_300,
        (0..30).map { Position(1_794_830_000L + it * 10, 48.4254 + it * 0.0001, 2.6402) },
    )

    @Test
    fun choix_du_mode_formateur_et_du_couloir() {
        val normal = mutableListOf<LaidTrail?>()
        val instructor = mutableListOf<Pair<String, Int>>()
        rule.setContent {
            MaterialTheme {
                TrailChoiceDialog(
                    listOf(trail), now = trail.endedAt + 2700, onChoose = { normal += it }, onDismiss = {},
                    onChooseInstructor = { t, m -> instructor += t.id to m }, corridorDefault = 20,
                )
            }
        }
        rule.onNodeWithText("La piste ne sera affichée qu'à l'arrivée du chien.").assertIsDisplayed()
        rule.onNodeWithTag("instructor-mode").performClick()
        rule.onNodeWithText("la piste est affichée", substring = true).assertIsDisplayed()
        // En mode formateur, une piste est obligatoire.
        rule.onNodeWithText("Sans piste").assertDoesNotExist()
        rule.onNodeWithTag("corridor-30").performClick()
        rule.onNodeWithText("Posée il y a 45 min").performClick()

        assertEquals(listOf("t1" to 30), instructor)
        assertEquals(emptyList<LaidTrail?>(), normal)
    }

    @Test
    fun sans_option_formateur_le_choix_reste_celui_d_avant() {
        rule.setContent { MaterialTheme { TrailChoiceDialog(listOf(trail), now = trail.endedAt + 60, onChoose = {}, onDismiss = {}) } }
        rule.onNodeWithTag("instructor-mode").assertDoesNotExist()
    }

    private fun recordingState(status: CorridorStatus?) = UiState(
        ready = true,
        recording = ActiveRecording("r1", "Rex", TrackSource.PHONE, Instant.now().epochSecond - 120, trailId = "t1", corridorMeters = 20),
        trails = listOf(trail), trailLine = trail.points, corridor = status,
    )

    @Test
    fun bandeau_dans_puis_hors_du_couloir() {
        val state = androidx.compose.runtime.mutableStateOf(recordingState(null))
        rule.setContent { MaterialTheme { TrackScreen(state.value, TrackActions()) } }

        rule.onNodeWithTag("corridor-status").assertTextContains("En attente du GPS", substring = true)
        // La piste n'est pas masquée en mode formateur.
        rule.onNodeWithText("affichée à l'arrivée", substring = true).assertDoesNotExist()

        state.value = recordingState(CorridorStatus(8.2, outside = false, exits = 0, nearEnd = false))
        rule.onNodeWithTag("corridor-status").assertTextContains("Mode formateur · couloir de 20 m", substring = true)
        rule.onNodeWithText("À 8 m de la piste").assertIsDisplayed()

        state.value = recordingState(CorridorStatus(32.4, outside = true, exits = 2, nearEnd = false))
        rule.onNodeWithText("HORS COULOIR · 32 m de la piste").assertIsDisplayed()
        rule.onNodeWithTag("corridor-status").assertTextContains("2 sorties", substring = true)

        state.value = recordingState(CorridorStatus(4.0, outside = false, exits = 2, nearEnd = true))
        rule.onNodeWithText("Arrivée proche · à 4 m de la piste").assertIsDisplayed()
    }

    @Test
    fun recherche_enregistree_en_mode_formateur() {
        val session = TrailSession(
            "s1", "Rex", TrackSource.PHONE, 1_794_906_000, 1_794_906_600,
            listOf(trail.points), trailId = "t1", trail = trail.points, trailEndedAt = trail.endedAt,
            corridorMeters = 20, corridorExits = 1,
        )
        rule.setContent {
            MaterialTheme {
                TrackScreen(UiState(ready = true, openedSession = session, segments = session.segments, trailLine = session.trail), TrackActions())
            }
        }
        rule.onNodeWithText("Mode formateur : couloir de 20 m · 1 sortie", substring = true).assertIsDisplayed()
    }
}
