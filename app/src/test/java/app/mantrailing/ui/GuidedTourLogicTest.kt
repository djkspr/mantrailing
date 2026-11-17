package app.mantrailing.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuidedTourLogicTest {
    @Test
    fun `six etapes - bienvenue au centre puis les elements de l'accueil`() {
        assertEquals(6, TOUR_STEPS.size)
        assertNull(TOUR_STEPS.first().target)
        assertEquals(
            listOf(TourTarget.LAY, TourTarget.RECORD, TourTarget.TITLE, TourTarget.MENU, TourTarget.LOCATE),
            TOUR_STEPS.drop(1).map { it.target },
        )
    }

    @Test
    fun `bouton suivant, puis c'est parti a la derniere etape`() {
        assertEquals("Suivant", tourNextLabel(0))
        assertEquals("Suivant", tourNextLabel(TOUR_STEPS.lastIndex - 1))
        assertEquals("C'est parti", tourNextLabel(TOUR_STEPS.lastIndex))
    }

    @Test
    fun `carte d'explication du cote ou il y a de la place`() {
        assertEquals(CardPlacement.CENTER, cardPlacement(null, 2000f))
        // Bouton en haut de l'écran : explication en dessous ; « Me localiser » en bas : au-dessus.
        assertEquals(CardPlacement.BELOW, cardPlacement(Rect(0f, 200f, 500f, 300f), 2000f))
        assertEquals(CardPlacement.ABOVE, cardPlacement(Rect(700f, 1800f, 1000f, 1900f), 2000f))
    }
}
