package app.mantrailing.session

import app.mantrailing.demo.DemoSession
import app.mantrailing.tractive.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SearchNoteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val track = (0..10).map { Position(1_000L + it * 10, 48.0 + it * 0.001, 2.0) } // un point toutes les 10 s

    @Test
    fun `note placée sur la position la plus proche dans le temps`() {
        val located = locateNotes(listOf(SearchNote("n", 1_034, NoteType.HESITATION)), track).single()
        assertEquals(48.003, located.lat!!, 1e-9) // 1 034 s : plus proche du point à 1 030 s
        assertEquals(2.0, located.lon!!, 1e-9)
    }

    @Test
    fun `note trop éloignée de la trace dans le temps - pas de position`() {
        val far = SearchNote("n", 1_100 + NOTE_MAX_GAP_SECONDS + 1, NoteType.FOUND)
        assertNull(locateNotes(listOf(far), track).single().lat)
    }

    @Test
    fun `sans trace (collier pas encore transmis) - notes gardées telles quelles`() {
        val notes = listOf(SearchNote("n", 1_000, NoteType.TRACK_LOST))
        assertEquals(notes, locateNotes(notes, emptyList()))
    }

    @Test
    fun `libellé - le texte de la note libre, sinon le type`() {
        assertEquals("Hésitation", SearchNote("n", 0, NoteType.HESITATION, comment = "au carrefour").label)
        assertEquals("Biche croisée", SearchNote("n", 0, NoteType.FREE, comment = "Biche croisée").label)
        assertEquals("Note libre", SearchNote("n", 0, NoteType.FREE).label)
    }

    @Test
    fun `notes de la recherche en cours - ajout, annulation de la dernière, relecture`() {
        val buffer = NotesBuffer(File(tmp.root, "recording/notes.jsonl"))
        buffer.append(SearchNote("1", 10, NoteType.HESITATION))
        buffer.append(SearchNote("2", 20, NoteType.TRACK_LOST))
        buffer.append(SearchNote("3", 30, NoteType.DISTRACTION))

        assertEquals("3", buffer.removeLast()!!.id)
        assertEquals(listOf("1", "2"), buffer.read().map { it.id })
        buffer.clear()
        assertNull(buffer.removeLast())
    }

    @Test
    fun `une recherche garde ses notes`() {
        val repo = SessionRepository(tmp.root)
        val notes = listOf(SearchNote("1", 10, NoteType.NEGATIVE, comment = "refuse le chemin", lat = 48.0, lon = 2.0))
        repo.save(TrailSession("s", "Rex", TrackSource.PHONE, 0, 600, notes = notes))
        assertEquals(notes, repo.load("s")!!.notes)
    }

    @Test
    fun `notes rapides - 6 par défaut, ordre gardé, noms inconnus ignorés`() {
        assertEquals(DEFAULT_QUICK_NOTES, parseQuickNotes(null))
        assertEquals(6, DEFAULT_QUICK_NOTES.size)
        val custom = listOf(NoteType.FOUND, NoteType.WIND)
        assertEquals(custom, parseQuickNotes(formatQuickNotes(custom)))
        assertEquals(listOf(NoteType.WIND), parseQuickNotes("NOTE_FUTURE,WIND,WIND"))
        assertTrue(parseQuickNotes("").isEmpty())
        assertEquals(MAX_QUICK_NOTES, parseQuickNotes(NoteType.entries.joinToString(",") { it.name }).size)
    }

    @Test
    fun `chaque type de note a une famille et un libellé`() {
        NoteType.entries.forEach { assertTrue(it.name, it.label.isNotBlank()) }
        // La liste validée : 23 notes (truffe au sol et truffe haute séparées) + la note libre.
        assertEquals(24, NoteType.entries.size)
    }

    @Test
    fun `démo - les notes sont placées sur la trace`() {
        assertEquals(5, DemoSession.notes.size)
        assertTrue(DemoSession.notes.all { it.lat != null && it.lon != null })
        assertEquals(NoteType.FOUND, DemoSession.notes.last().type)
    }
}
