package app.mantrailing.track

import app.mantrailing.session.LaidTrail
import app.mantrailing.session.NoteType
import app.mantrailing.session.SearchNote
import app.mantrailing.session.SessionSummary
import app.mantrailing.session.TrackSource
import app.mantrailing.session.TrailSession
import app.mantrailing.tractive.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneOffset

class ProgressTest {
    private val day = 86_400L
    private val now = 1_794_906_000L // 17/11/2026 09:00 UTC
    private val utc = ZoneOffset.UTC

    // Piste de 0,002° vers le nord (≈ 222 m) ; le chien la suit à [offset] degrés de longitude près.
    private val trail = (0..20).map { Position(it * 30L, 48.0 + it * 0.0001, 2.0) }
    private fun dog(start: Long, offset: Double) = listOf((0..20).map { Position(start + it * 10L, 48.0 + it * 0.0001, 2.0 + offset) })

    private fun search(
        id: String, daysAgo: Long, ageMinutes: Long? = 30, offset: Double = 0.0001, outcome: NoteType? = NoteType.FOUND,
    ): TrailSession {
        val start = now - daysAgo * day
        return TrailSession(
            id, "Rex", TrackSource.PHONE, start, start + 600, dog(start, offset),
            trail = if (ageMinutes != null) trail else emptyList(),
            trailEndedAt = ageMinutes?.let { start - it * 60 },
            notes = listOfNotNull(outcome?.let { SearchNote("$id-end", start + 600, it) }),
            dogId = "d1",
        )
    }

    @Test
    fun `resume d'une recherche - age, longueur, ecart, issue`() {
        val p = searchPoint(search("s1", 1, ageMinutes = 45, offset = 0.0001))
        assertEquals(45 * 60L, p.trailAgeSeconds)
        assertEquals(222.0, p.trailLengthMeters!!, 2.0)
        assertEquals(7.4, p.meanDeviationMeters!!, 0.5)
        assertEquals(true, p.reachedEnd)
        assertEquals(Outcome.FOUND, p.outcome)
    }

    @Test
    fun `recherche qui n'est plus qu'en ligne - resume et piste posee connue`() {
        val archived = search("s1", 3).copy(
            segments = emptyList(), trail = emptyList(), trailId = "t1", archived = true,
            summary = SessionSummary(850.0, 120, 12.0, false),
        )
        val p = searchPoint(archived, listOf(LaidTrail("t1", 0, 600, lengthMeters = 640.0)))
        assertEquals(850.0, p.dogDistanceMeters!!, 0.0)
        assertEquals(12.0, p.meanDeviationMeters!!, 0.0)
        assertEquals(false, p.reachedEnd)
        assertEquals(640.0, p.trailLengthMeters!!, 0.0)
        assertEquals(30 * 60L, p.trailAgeSeconds) // trailEndedAt est gardé avec le résumé
    }

    @Test
    fun `recherche sans piste - ni age, ni longueur, ni ecart`() {
        val p = searchPoint(search("s1", 1, ageMinutes = null))
        assertNull(p.trailAgeSeconds)
        assertNull(p.trailLengthMeters)
        assertNull(p.meanDeviationMeters)
        assertTrue(p.dogDistanceMeters!! > 200)
    }

    @Test
    fun `issues - trouve, abandon, mauvaise identification, non precisee`() {
        assertEquals(Outcome.ABANDON, outcomeOf(search("a", 1, outcome = NoteType.ABANDON)))
        assertEquals(Outcome.WRONG_ID, outcomeOf(search("b", 1, outcome = NoteType.WRONG_ID)))
        assertNull(outcomeOf(search("c", 1, outcome = null)))
    }

    @Test
    fun `bilan - reussite parmi les issues notees, moyennes, ordre chronologique`() {
        val sessions = listOf(
            search("s3", 1, ageMinutes = 90, outcome = NoteType.FOUND),
            search("s1", 20, ageMinutes = 30, outcome = NoteType.ABANDON),
            search("s2", 10, ageMinutes = 60, outcome = null),
        )
        val progress = progressOf(sessions, now = now, zone = utc)

        assertEquals(listOf("s1", "s2", "s3"), progress.points.map { it.id })
        assertEquals(3, progress.searchCount)
        assertEquals(0.5, progress.successRate!!, 1e-9) // 1 trouvé sur 2 issues notées
        assertEquals(3600.0, progress.meanTrailAgeSeconds!!, 1e-9)
        assertTrue(progress.hasEnoughData)
    }

    @Test
    fun `periodes - 3 mois, 12 mois, tout`() {
        val sessions = listOf(search("old", 400), search("year", 200), search("recent", 30))
        assertEquals(listOf("recent"), progressOf(sessions, period = ProgressPeriod.THREE_MONTHS, now = now).points.map { it.id })
        assertEquals(listOf("year", "recent"), progressOf(sessions, period = ProgressPeriod.YEAR, now = now).points.map { it.id })
        assertEquals(3, progressOf(sessions, period = ProgressPeriod.ALL, now = now).searchCount)
        assertFalse(progressOf(sessions, period = ProgressPeriod.THREE_MONTHS, now = now).hasEnoughData)
    }

    @Test
    fun `tendances - 5 dernieres comparees aux 5 precedentes`() {
        // Pistes de plus en plus vieilles (10 → 100 min), écart qui diminue : le chien progresse.
        val sessions = (1..10).map { i -> search("s$i", 100L - i, ageMinutes = i * 10L, offset = 0.0002 / i) }
        val progress = progressOf(sessions, now = now, zone = utc)

        val age = progress.trailAgeTrend!!
        assertEquals(80 * 60.0, age.recent, 1e-6) // moyenne de 60..100 min
        assertEquals(30 * 60.0, age.previous, 1e-6) // moyenne de 10..50 min
        assertEquals(Trend.UP, age.trend())
        assertEquals(Trend.DOWN, progress.deviationTrend!!.trend())
    }

    @Test
    fun `tendance stable sous 10 pour cent, et pas de tendance avec une seule recherche`() {
        assertEquals(Trend.STABLE, TrendValue(105.0, 100.0).trend())
        assertEquals(Trend.STABLE, TrendValue(3.0, 2.0).trend(minDelta = 2.0))
        assertNull(progressOf(listOf(search("s1", 1)), now = now).trailAgeTrend)
    }

    @Test
    fun `reussite - tendance sur les recherches dont l'issue est notee`() {
        val sessions = listOf(
            search("a", 9, outcome = NoteType.ABANDON), search("b", 8, outcome = NoteType.ABANDON),
            search("c", 7, outcome = null), search("d", 6, outcome = NoteType.FOUND), search("e", 5, outcome = NoteType.FOUND),
        )
        val trend = progressOf(sessions, now = now).successTrend!!
        assertEquals(1.0, trend.recent, 1e-9)
        assertEquals(0.0, trend.previous, 1e-9)
    }

    @Test
    fun `records - piste la plus vieille et la plus longue trouvees, les abandons ne comptent pas`() {
        val longTrail = (0..40).map { Position(it * 30L, 48.0 + it * 0.0001, 2.0) }
        val sessions = listOf(
            search("vieille", 5, ageMinutes = 180),
            search("abandon", 4, ageMinutes = 600, outcome = NoteType.ABANDON),
            search("longue", 3, ageMinutes = 20).copy(trail = longTrail),
        )
        val progress = progressOf(sessions, now = now)
        assertEquals("vieille", progress.oldestFound!!.id)
        assertEquals("longue", progress.longestFound!!.id)
    }

    @Test
    fun `recherches par mois, mois vides completes`() {
        val sessions = listOf(search("a", 100), search("b", 99), search("c", 30))
        val monthly = progressOf(sessions, now = now, zone = utc).monthly
        assertEquals(listOf(YearMonth.of(2026, 8) to 2, YearMonth.of(2026, 10) to 1), monthly.map { it.month to it.count })
        assertEquals(
            listOf(2, 0, 1),
            filledMonths(monthly).map { it.count },
        )
    }

    @Test
    fun `aucune recherche - bilan vide`() {
        val progress = progressOf(emptyList(), now = now)
        assertEquals(0, progress.searchCount)
        assertNull(progress.successRate)
        assertNull(progress.oldestFound)
        assertTrue(progress.monthly.isEmpty())
    }
}
