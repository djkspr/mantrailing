package app.mantrailing.export

import app.mantrailing.session.LaidTrail
import app.mantrailing.session.NoteType
import app.mantrailing.session.SearchNote
import app.mantrailing.session.TrackSource
import app.mantrailing.session.TrailSession
import app.mantrailing.tractive.Position
import app.mantrailing.weather.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ReportTest {
    private val paris = ZoneId.of("Europe/Paris")

    // Mardi 17/11/2026 09:00 UTC = 10:00 à Paris.
    private val start = 1_794_906_000L

    // Piste de 0,002° vers le nord (≈ 222 m), chien qui la suit à 0,0001° près (≈ 7 m).
    private val trail = (0..20).map { Position(start - 1_800 + it * 30L, 48.0 + it * 0.0001, 2.0) }
    private val dog = (0..20).map { Position(start + it * 10L, 48.0 + it * 0.0001, 2.0001) }

    private val session = TrailSession(
        id = "s1", dogName = "Rex", source = TrackSource.PHONE, startedAt = start, endedAt = start + 600,
        segments = listOf(dog), trail = trail, trailEndedAt = start - 1_200,
        notes = listOf(
            SearchNote("n2", start + 400, NoteType.FOUND, lat = 48.002, lon = 2.0),
            SearchNote("n1", start + 125, NoteType.HESITATION, "au carrefour", lat = 48.001, lon = 2.0),
        ),
        trailNotes = listOf(SearchNote("t1", start - 1_500, NoteType.DANGER, "chien au portail", lat = 48.0005, lon = 2.0)),
        weather = Weather(start, 8.4, 11.2, 270, 15.0, 88, 0.0, 95),
    )

    private fun rows(report: Report) = report.sections.flatMap { s -> s.rows.map { "${s.title} / ${it.label}" to it.value } }.toMap()

    @Test
    fun `rapport d'une recherche - seance, piste, comparaison, meteo`() {
        val report = sessionReport(session, "Club du dimanche", paris)
        val rows = rows(report)

        assertEquals("Recherche de Rex", report.title)
        assertEquals("mardi 17 novembre 2026 à 10:00", report.subtitle)
        assertEquals("mardi 17 novembre 2026, de 10:00 à 10:10", rows["Séance / Date"])
        assertEquals("10 min", rows["Séance / Durée"])
        assertEquals("GPS du téléphone", rows["Séance / Trace prise par"])
        assertEquals("Club du dimanche", rows["Séance / Espace"])
        assertEquals("Trouvé", rows["Séance / Issue"])
        assertEquals("222 m", rows["Piste suivie / Longueur de la piste"])
        assertEquals("20 min", rows["Piste suivie / Âge au départ du chien"])
        assertEquals("21", rows["Trace du chien / Points enregistrés"])
        assertEquals("7 m", rows["Comparaison avec la piste / Écart moyen à la piste"])
        assertEquals("atteinte", rows["Comparaison avec la piste / Arrivée"])
        assertTrue(rows["Météo / Au départ du chien"]!!.startsWith("8 °C · vent d'ouest 11 km/h"))
        assertEquals("Mantrailing_recherche_Rex_2026-11-17_10h00.pdf", report.fileName)
    }

    @Test
    fun `notes dans l'ordre, heure et temps ecoule, signalements du traceur a part`() {
        val report = sessionReport(session, "Perso", paris)

        assertEquals(listOf("n1", "n2"), report.notes.map { it.id })
        assertEquals("10:02 · +2 min 5s", report.notes[0].offset)
        assertEquals("Le chien", report.notes[0].category)
        assertEquals("Hésitation — au carrefour", report.notes[0].text)

        val danger = report.trailerNotes.single()
        assertEquals("09:35", danger.offset) // début de la pose inconnu : heure seule
        assertEquals("Danger — chien au portail", danger.text)
        assertTrue(danger.warning)
    }

    @Test
    fun `recherche sans piste ni meteo - pas de rubriques vides`() {
        val alone = session.copy(trail = emptyList(), trailEndedAt = null, trailNotes = emptyList(), weather = null)
        val report = sessionReport(alone, "Perso", paris)

        assertEquals("Recherche sans piste enregistrée", rows(report)["Piste suivie / Piste"])
        assertFalse(report.sections.any { it.title == "Comparaison avec la piste" || it.title == "Météo" })
        assertTrue(report.trailerNotes.isEmpty())
    }

    @Test
    fun `rapport d'une piste posee, pas encore travaillee`() {
        val laid = LaidTrail(
            "p1", start - 1_800, start - 1_200, trail,
            notes = listOf(SearchNote("t1", start - 1_500, NoteType.ATTENTION, "route passante", lat = 48.0005, lon = 2.0)),
        )
        val report = trailReport(laid, "Perso", paris)
        val rows = rows(report)

        assertEquals("Piste posée", report.title)
        assertEquals("10 min", rows["Pose / Durée de la pose"])
        assertEquals("222 m", rows["Pose / Longueur"])
        assertNull(rows["Météo / Pendant la pose"])
        assertEquals("09:35 · +5 min", report.trailerNotes.single().offset)
        assertEquals("Mantrailing_piste_2026-11-17_09h30.pdf", report.fileName)
    }

    @Test
    fun `nom de fichier sans accent ni caractere genant`() {
        val report = sessionReport(session.copy(dogName = "Léo / Éclair"), "Perso", paris)
        assertEquals("Mantrailing_recherche_Leo-Eclair_2026-11-17_10h00.pdf", report.fileName)
    }

    @Test
    fun `rubrique carnet - champs remplis seulement, absente sans carnet`() {
        val log = app.mantrailing.session.TrainingLog(
            runner = "Julie", scentArticle = "T-shirt", terrain = listOf(app.mantrailing.session.Terrain.FOREST),
            difficulties = listOf(app.mantrailing.session.Difficulty.CROSSINGS), rating = 4,
        )
        val rows = rows(sessionReport(session.copy(log = log), "Perso", paris))
        assertEquals("Julie", rows["Carnet / Traceur"])
        assertEquals("T-shirt", rows["Carnet / Objet odorant"])
        assertEquals("Forêt", rows["Carnet / Terrain"])
        assertEquals("Croisements", rows["Carnet / Difficultés"])
        assertEquals("★★★★☆ (4/5)", rows["Carnet / Travail de l'équipe"])
        assertNull(rows["Carnet / Récompense"])
        assertFalse(sessionReport(session, "Perso", paris).sections.any { it.title == "Carnet" })
        val trail = LaidTrail("p1", start - 1_800, start - 1_200, trail, log = log.forSearch())
        assertEquals("Julie", rows(trailReport(trail, "Perso", paris))["Carnet / Traceur"])
    }
}
