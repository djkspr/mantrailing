package app.mantrailing.session

import app.mantrailing.tractive.Position
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Signalements du traceur pendant la pose. */
class TrailerNotesTest {
    private val points = (0..10).map { Position(1_000L + it * 10, 48.0 + it * 0.001, 2.0) }

    @Test
    fun `dangers et points d'attention annonces au maitre-chien, pas le reste`() {
        val trail = LaidTrail(
            "t", 1_000, 1_100, points,
            notes = listOf(
                SearchNote("1", 1_010, NoteType.DANGER, "Route passante"),
                SearchNote("2", 1_020, NoteType.SURFACE),
                SearchNote("3", 1_030, NoteType.ATTENTION),
                SearchNote("4", 1_040, NoteType.TRAILER_STOP),
            ),
        )
        assertEquals(listOf("1", "3"), trail.warnings.map { it.id })
        assertEquals("Danger — Route passante", trail.warnings[0].detail)
        assertEquals("Point d'attention", trail.warnings[1].detail)
    }

    @Test
    fun `signalements places sur le parcours du traceur`() {
        val located = locateNotes(listOf(SearchNote("1", 1_052, NoteType.DANGER)), points)
        assertEquals(48.005, located.single().lat!!, 1e-9)
    }

    @Test
    fun `les signalements du traceur ne deviennent pas des notes rapides de recherche`() {
        assertEquals(listOf(NoteType.HESITATION), parseQuickNotes("DANGER,HESITATION,ATTENTION"))
        assertTrue(LAYING_QUICK_NOTES.all { it.category in LAYING_CATEGORIES })
        assertFalse(NoteCategory.TRAILER in SEARCH_CATEGORIES)
    }

    @Test
    fun `piste enregistree avant les signalements toujours lisible`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(LaidTrail.serializer(), """{"id":"t","startedAt":1,"endedAt":2,"points":[]}""")
        assertTrue(old.notes.isEmpty())
        val search = json.decodeFromString(TrailSession.serializer(), """{"id":"s","dogName":"Rex","source":"PHONE","startedAt":1,"endedAt":2}""")
        assertTrue(search.trailNotes.isEmpty())
    }
}
