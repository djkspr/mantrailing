package app.mantrailing.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mantrailing.UiState
import app.mantrailing.session.Difficulty
import app.mantrailing.session.LaidTrail
import app.mantrailing.session.Terrain
import app.mantrailing.session.TrackSource
import app.mantrailing.session.TrailSession
import app.mantrailing.session.TrainingLog
import app.mantrailing.tractive.Position
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Carnet d'entraînement : saisie, reprise, affichage dans « Mes pistes » et sur la recherche ouverte. */
@RunWith(AndroidJUnit4::class)
class TrainingLogTest {
    @get:Rule
    val rule = createComposeRule()

    private val start = 1_794_906_000L
    private val points = (0..10).map { Position(start + it * 10L, 48.0 + it * 0.0002, 2.0) }

    @Test
    fun carnet_de_recherche_rempli_et_enregistre() {
        val saved = mutableListOf<TrainingLog>()
        val fromTrail = TrainingLog(runner = "Julie", terrain = listOf(Terrain.FOREST))
        rule.setContent {
            MaterialTheme {
                TrainingLogDialog(
                    fromTrail, forTrail = false, runnerSuggestions = listOf("Julie", "Marc"), scentSuggestions = listOf("Gant", "T-shirt"),
                    placeSuggestions = emptyList(), onSave = { saved += it }, onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("Carnet de la recherche").assertIsDisplayed()
        // Objet odorant choisi parmi les propositions.
        rule.onNodeWithText("T-shirt").performScrollTo().performClick()
        rule.onNodeWithTag("log-diff-CROSSINGS").performScrollTo().performClick()
        rule.onNodeWithTag("log-reward").performScrollTo().performTextInput("Jouet")
        rule.onNodeWithTag("log-star-4").performScrollTo().performClick()
        rule.onNodeWithTag("log-save").performClick()

        val log = saved.single()
        assertEquals("Julie", log.runner)
        assertEquals("T-shirt", log.scentArticle)
        assertEquals(listOf(Terrain.FOREST), log.terrain)
        assertEquals(listOf(Difficulty.CROSSINGS), log.difficulties)
        assertEquals("Jouet", log.reward)
        assertEquals(4, log.rating)
    }

    @Test
    fun carnet_de_piste_sans_note_ni_recompense_et_plus_tard() {
        var later = 0
        rule.setContent {
            MaterialTheme {
                TrainingLogDialog(null, forTrail = true, emptyList(), emptyList(), emptyList(), onSave = {}, onDismiss = { later++ })
            }
        }
        rule.onNodeWithText("Carnet de la piste").assertIsDisplayed()
        rule.onNodeWithTag("log-reward").assertDoesNotExist()
        rule.onNodeWithTag("log-star-1").assertDoesNotExist()
        rule.onNodeWithTag("log-later").performClick()
        assertEquals(1, later)
    }

    @Test
    fun recherche_ouverte_resume_du_carnet_et_bouton_carnet() {
        var opened = 0
        val session = TrailSession(
            "s1", "Rex", TrackSource.PHONE, start, start + 100, listOf(points),
            log = TrainingLog(runner = "Julie", terrain = listOf(Terrain.CITY), rating = 5),
        )
        rule.setContent {
            MaterialTheme { TrackScreen(UiState(ready = true, openedSession = session, segments = session.segments), TrackActions(onOpenLog = { opened++ })) }
        }
        rule.onNodeWithText("Carnet : Traceur : Julie · Ville · ★★★★★", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Carnet").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun mes_pistes_resume_et_filtre_par_terrain() {
        val enForet = TrailSession("s1", "Rex", TrackSource.PHONE, start, start + 100, log = TrainingLog(runner = "Julie", terrain = listOf(Terrain.FOREST)))
        val enVille = TrailSession("s2", "Rex", TrackSource.PHONE, start + 1_000, start + 1_100, log = TrainingLog(runner = "Marc", terrain = listOf(Terrain.CITY)))
        val piste = LaidTrail("t1", start - 2_000, start - 1_900, points, log = TrainingLog(place = "Parc du château", terrain = listOf(Terrain.PARK)))
        rule.setContent { MaterialTheme { SessionsScreen(listOf(enVille, enForet), listOf(piste), emptyList(), SessionsActions()) } }

        rule.onNodeWithText("Traceur : Julie · Forêt", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Parc du château · Parc", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("terrain-filter").assertIsDisplayed()
        rule.onNodeWithText("Ville").performClick()
        rule.onNodeWithText("Traceur : Marc · Ville", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Traceur : Julie · Forêt", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Parc du château", substring = true).assertDoesNotExist()
    }
}
