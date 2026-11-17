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
import app.mantrailing.session.Dog
import app.mantrailing.session.NoteType
import app.mantrailing.session.SearchNote
import app.mantrailing.session.TrackSource
import app.mantrailing.session.TrailSession
import app.mantrailing.tractive.Position
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ProgressScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val rex = Dog("d1", "Rex")
    private val nala = Dog("d2", "Nala")
    private val now = Instant.now().epochSecond
    private val trail = (0..20).map { Position(it * 30L, 48.0 + it * 0.0001, 2.0) }

    private fun search(id: String, dog: Dog, daysAgo: Long, ageMinutes: Long, outcome: NoteType = NoteType.FOUND): TrailSession {
        val start = now - daysAgo * 86_400
        return TrailSession(
            id, dog.name, TrackSource.PHONE, start, start + 600,
            listOf((0..20).map { Position(start + it * 10L, 48.0 + it * 0.0001, 2.0001) }),
            trail = trail, trailEndedAt = start - ageMinutes * 60,
            notes = listOf(SearchNote("$id-end", start + 600, outcome)), dogId = dog.id,
        )
    }

    private val sessions = listOf(
        search("r1", rex, 60, 20, NoteType.ABANDON),
        search("r2", rex, 40, 30),
        search("r3", rex, 20, 45),
        search("r4", rex, 5, 90),
        search("n1", nala, 10, 15),
    )

    @Test
    fun progres_d_un_chien_tuiles_records_et_courbes() {
        var back = 0
        rule.setContent { MaterialTheme { ProgressScreen(sessions, emptyList(), listOf(rex, nala), rex.id, onBack = { back++ }) } }

        rule.onNodeWithText("Progrès de Rex").assertIsDisplayed()
        rule.onNodeWithTag("tile-count").assertIsDisplayed()
        rule.onNodeWithText("4").assertIsDisplayed()
        rule.onNodeWithText("75 %").assertIsDisplayed() // 3 trouvés sur 4
        rule.onNodeWithText("Piste la plus vieille trouvée : 1 h 30", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("chart-age").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Âge de la piste au départ : de 20 min à 1 h 30", substring = true).assertExists()
        rule.onNodeWithTag("chart-deviation").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("chart-months").performScrollTo().assertIsDisplayed()

        rule.onNodeWithContentDescription("Retour").performClick()
        assertEquals(1, back)
    }

    @Test
    fun autre_chien_et_periode_avec_trop_peu_de_recherches() {
        rule.setContent { MaterialTheme { ProgressScreen(sessions, emptyList(), listOf(rex, nala), rex.id, onBack = {}) } }

        rule.onNodeWithText("Nala").performClick()
        rule.onNodeWithText("Progrès de Nala").assertIsDisplayed()
        rule.onNodeWithTag("progress-empty").assertIsDisplayed()
        rule.onNodeWithText("Une seule recherche", substring = true).assertIsDisplayed()

        rule.onNodeWithText("Rex").performClick()
        rule.onNodeWithText("3 mois").performClick()
        rule.onNodeWithTag("tile-count").assertIsDisplayed()
    }

    @Test
    fun mes_pistes_ouvre_les_progres_du_chien_filtre() {
        val opened = mutableListOf<String?>()
        rule.setContent {
            MaterialTheme { SessionsScreen(sessions, emptyList(), listOf(rex, nala), SessionsActions(onProgress = { opened += it })) }
        }
        rule.onNodeWithTag("open-progress").performClick()
        rule.onNodeWithText("Nala").performClick()
        rule.onNodeWithTag("open-progress").performClick()
        assertEquals(listOf(null, nala.id), opened)
    }
}
