package app.mantrailing.session

import app.mantrailing.sync.TeamDirectory
import app.mantrailing.tractive.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveTest {
    private val day = 86_400L
    private val now = 1_800_000_000L
    private val track = listOf(listOf(Position(0, 48.0, 2.0), Position(3, 48.001, 2.0), Position(6, 48.002, 2.0)))
    private val trailPoints = listOf(Position(0, 48.0, 2.0), Position(3, 48.002, 2.0))

    private fun session(id: String, endedDaysAgo: Long, updatedAt: Long? = null) = TrailSession(
        id, "Rex", TrackSource.PHONE, now - endedDaysAgo * day - 600, now - endedDaysAgo * day, track,
        trail = trailPoints, notes = listOf(SearchNote("n", 0, NoteType.FOUND)), updatedAt = updatedAt,
    )

    @Test
    fun `seules les recherches anciennes et confirmees par le serveur quittent le telephone`() {
        val old = session("old", endedDaysAgo = 40)
        val recent = session("recent", endedDaysAgo = 5)
        val notSent = session("notSent", endedDaysAgo = 40)
        val modifiedSince = session("modified", endedDaysAgo = 40, updatedAt = 999)
        val confirmed = mapOf("old" to old.version, "recent" to recent.version, "modified" to 1L)

        val result = sessionsToArchive(listOf(old, recent, notSent, modifiedSince), { confirmed[it.id] }, now, keepDays = 30)

        assertEquals(listOf("old"), result.map { it.id })
    }

    @Test
    fun `une recherche archivee garde son bilan et ses notes, pas sa trace`() {
        val s = session("s", endedDaysAgo = 40)
        val a = archived(s)

        assertTrue(a.archived)
        assertTrue(a.segments.isEmpty() && a.trail.isEmpty())
        assertEquals(s.notes, a.notes)
        assertEquals(s.version, a.version)
        assertEquals(3, a.summary!!.pointCount)
        assertEquals(222.0, a.summary!!.distanceMeters, 2.0)
        assertTrue(a.summary!!.reachedEnd!!)
        // Déjà archivée : pas une seconde fois.
        assertTrue(sessionsToArchive(listOf(a), { a.version }, now, 30).isEmpty())
    }

    @Test
    fun `piste posee archivee avec sa longueur`() {
        val trail = LaidTrail("t", now - 40 * day - 300, now - 40 * day, trailPoints)
        assertEquals(listOf(trail), trailsToArchive(listOf(trail), { trail.version }, now, 30))
        val a = archived(trail)
        assertTrue(a.points.isEmpty())
        assertEquals(222.0, a.lengthMeters!!, 2.0)
        assertFalse(trailsToArchive(listOf(trail), { null }, now, 30).isNotEmpty())
    }

    @Test
    fun `codes d'invitation lisibles et saisis librement`() {
        val code = TeamDirectory.newInviteCode()
        assertEquals(8, code.length)
        assertTrue(code.none { it in "01OIL" })
        assertNotEquals(code, TeamDirectory.newInviteCode())
        assertEquals("ABCD-EFGH", TeamDirectory.formatInviteCode("ABCDEFGH"))
        assertEquals("ABCDEFGH", TeamDirectory.normalizeInviteCode(" abcd-efgh "))
    }
}
