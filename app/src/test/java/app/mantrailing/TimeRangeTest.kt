package app.mantrailing

import app.mantrailing.ui.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class TimeRangeTest {
    @Test
    fun `trois dernières heures en milieu de journée`() {
        val r = TimeRange.lastHours(3, LocalDateTime.of(2026, 11, 16, 15, 42, 30))
        assertEquals(TimeRange(LocalDate.of(2026, 11, 16), LocalTime.of(12, 42), LocalTime.of(15, 43)), r)
        assertTrue(r.isValid)
    }

    @Test
    fun `ne remonte pas avant minuit`() {
        val r = TimeRange.lastHours(3, LocalDateTime.of(2026, 11, 16, 1, 10))
        assertEquals(LocalTime.MIDNIGHT, r.start)
        assertEquals(LocalDate.of(2026, 11, 16), r.date)
    }

    @Test
    fun `reste valide juste avant minuit`() {
        val r = TimeRange.lastHours(1, LocalDateTime.of(2026, 11, 16, 23, 59, 40))
        assertEquals(LocalTime.of(23, 59), r.end)
        assertTrue(r.isValid)
    }

    @Test
    fun `fin avant début est invalide`() {
        assertFalse(TimeRange(LocalDate.of(2026, 1, 1), LocalTime.of(10, 0), LocalTime.of(9, 0)).isValid)
    }

    @Test
    fun `conversion en instants selon le fuseau`() {
        val r = TimeRange(LocalDate.of(2026, 7, 1), LocalTime.of(10, 0), LocalTime.of(11, 30))
        val paris = ZoneId.of("Europe/Paris") // UTC+2 en été
        assertEquals(Instant.parse("2026-07-01T08:00:00Z"), r.from(paris))
        assertEquals(Instant.parse("2026-07-01T09:30:00Z"), r.to(paris))
    }

    @Test
    fun `format des durées`() {
        assertEquals("45 s", formatDuration(45))
        assertEquals("2 min 30s", formatDuration(150))
        assertEquals("5 min", formatDuration(300))
        assertEquals("2 h 04", formatDuration(2 * 3600 + 4 * 60))
    }
}
