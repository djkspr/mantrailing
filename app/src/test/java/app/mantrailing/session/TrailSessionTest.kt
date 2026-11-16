package app.mantrailing.session

import app.mantrailing.track.PHONE_GPS_SENSOR
import app.mantrailing.track.TrackStats
import app.mantrailing.tractive.Position
import app.mantrailing.ui.formatSessionDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

class TrailSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun session(id: String, startedAt: Long, points: Int = 3) = TrailSession(
        id = id, dogName = "Rex", source = TrackSource.PHONE, startedAt = startedAt, endedAt = startedAt + 600,
        segments = listOf((0 until points).map { Position(startedAt + it, 44.0 + it * 0.0001, 2.0, null, 4.0, PHONE_GPS_SENSOR) }),
    )

    // --- Stockage des pistes ---

    @Test
    fun `une piste enregistrée se relit à l'identique`() {
        val repo = SessionRepository(File(tmp.root, "sessions"))
        val s = session("a", 1_000)
        repo.save(s)
        assertEquals(s, repo.load("a"))
    }

    @Test
    fun `liste la plus récente d'abord`() {
        val repo = SessionRepository(tmp.root)
        repo.save(session("ancienne", 1_000))
        repo.save(session("recente", 5_000))
        repo.save(session("milieu", 3_000))
        assertEquals(listOf("recente", "milieu", "ancienne"), repo.list().map { it.id })
    }

    @Test
    fun `réenregistrer une piste la remplace`() {
        val repo = SessionRepository(tmp.root)
        repo.save(session("a", 1_000, points = 0))
        repo.save(session("a", 1_000, points = 5))
        assertEquals(1, repo.list().size)
        assertEquals(5, repo.load("a")!!.segments.single().size)
    }

    @Test
    fun `suppression`() {
        val repo = SessionRepository(tmp.root)
        repo.save(session("a", 1_000))
        repo.delete("a")
        assertTrue(repo.list().isEmpty())
        assertNull(repo.load("a"))
    }

    @Test
    fun `un fichier abîmé n'empêche pas de lire les autres pistes`() {
        val repo = SessionRepository(tmp.root)
        repo.save(session("ok", 1_000))
        File(tmp.root, "abime.json").writeText("{ pas du json")
        assertEquals(listOf("ok"), repo.list().map { it.id })
    }

    @Test
    fun `dossier absent - aucune piste`() {
        assertTrue(SessionRepository(File(tmp.root, "inexistant")).list().isEmpty())
    }

    // --- Tampon GPS de la piste en cours ---

    @Test
    fun `tampon - positions ajoutées une à une puis relues dans l'ordre`() {
        val buffer = RecordingBuffer(File(tmp.root, "recording/current.jsonl"))
        val points = (0 until 4).map { Position(it.toLong(), 44.0, 2.0 + it, 100.0, 5.0, PHONE_GPS_SENSOR) }
        points.forEach(buffer::append)
        assertEquals(points, buffer.read())
        buffer.clear()
        assertTrue(buffer.read().isEmpty())
    }

    @Test
    fun `tampon - une ligne tronquée (arrêt brutal) est ignorée`() {
        val file = File(tmp.root, "current.jsonl")
        val buffer = RecordingBuffer(file)
        buffer.append(Position(1, 44.0, 2.0))
        file.appendText("{\"time\":2,\"lat\":44.")
        assertEquals(1, buffer.read().size)
    }

    // --- Choix de la source ---

    @Test
    fun `sans collier connecté - GPS du téléphone, sans question`() {
        assertEquals(StartDecision.Start(TrackSource.PHONE), decideSource(null, tractiveConnected = false))
        assertEquals(StartDecision.Start(TrackSource.PHONE), decideSource(TrackSource.TRACTIVE, tractiveConnected = false))
    }

    @Test
    fun `collier connecté et aucun choix - on propose le collier`() {
        assertEquals(StartDecision.AskSource, decideSource(null, tractiveConnected = true))
    }

    @Test
    fun `collier connecté - le choix enregistré est repris`() {
        assertEquals(StartDecision.Start(TrackSource.TRACTIVE), decideSource(TrackSource.TRACTIVE, tractiveConnected = true))
        assertEquals(StartDecision.Start(TrackSource.PHONE), decideSource(TrackSource.PHONE, tractiveConnected = true))
    }

    @Test
    fun `positions du téléphone trop imprécises écartées`() {
        assertTrue(acceptPhoneFix(4f))
        assertTrue(acceptPhoneFix(MAX_PHONE_ACCURACY_M))
        assertFalse(acceptPhoneFix(45f))
        assertFalse(acceptPhoneFix(null))
    }

    @Test
    fun `le GPS du téléphone compte comme du GPS dans le bilan`() {
        assertEquals(1.0, TrackStats.of(session("a", 0).segments).gpsShare, 0.0)
    }

    @Test
    fun `date d'une piste en français`() {
        // 16/11/2026 13:02 UTC = 14:02 à Paris
        assertEquals("lun. 16 nov. à 14:02", formatSessionDate(1_794_834_120, ZoneId.of("Europe/Paris")))
    }
}
