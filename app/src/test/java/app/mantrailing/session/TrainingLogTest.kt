package app.mantrailing.session

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingLogTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val full = TrainingLog(
        runner = "Julie", scentArticle = "T-shirt", place = "Forêt de Fontainebleau",
        terrain = listOf(Terrain.FOREST, Terrain.CITY), difficulties = listOf(Difficulty.CROSSINGS, Difficulty.STRONG_WIND),
        reward = "Jouet", rating = 4, comment = "Belle reprise après le carrefour",
    )

    @Test
    fun `ancienne recherche sans carnet toujours lisible`() {
        val old = """{"id":"s1","dogName":"Rex","source":"PHONE","startedAt":1,"endedAt":2}"""
        val session = json.decodeFromString(TrailSession.serializer(), old)
        assertNull(session.log)
        val trail = json.decodeFromString(LaidTrail.serializer(), """{"id":"t1","startedAt":1,"endedAt":2}""")
        assertNull(trail.log)
    }

    @Test
    fun `carnet enregistre puis relu, terrains et difficultes par leur nom`() {
        val session = TrailSession("s1", "Rex", TrackSource.PHONE, 1, 2, log = full)
        val text = json.encodeToString(TrailSession.serializer(), session)
        assertTrue(text.contains("\"terrain\":[\"FOREST\",\"CITY\"]"))
        assertEquals(session, json.decodeFromString(TrailSession.serializer(), text))
    }

    @Test
    fun `la recherche reprend ce que le traceur a note, pas la note ni la recompense`() {
        assertEquals(
            TrainingLog("Julie", "T-shirt", "Forêt de Fontainebleau", listOf(Terrain.FOREST, Terrain.CITY), listOf(Difficulty.CROSSINGS, Difficulty.STRONG_WIND)),
            full.forSearch(),
        )
    }

    @Test
    fun `nettoyage - champs vides retires, note bornee, carnet vide supprime`() {
        assertNull(TrainingLog(runner = "  ", comment = "", rating = 0).cleaned())
        assertEquals(TrainingLog(runner = "Julie", rating = null, terrain = listOf(Terrain.PARK)),
            TrainingLog(runner = " Julie ", rating = 9, terrain = listOf(Terrain.PARK, Terrain.PARK)).cleaned())
    }

    @Test
    fun `resume d'une ligne et etoiles`() {
        assertEquals("Traceur : Julie · Forêt de Fontainebleau · Forêt, Ville · ★★★★☆", full.summary)
        assertEquals("", TrainingLog().summary)
        assertEquals("★★☆☆☆", stars(2))
    }

    @Test
    fun `suggestions - valeurs deja saisies, les plus frequentes d'abord, puis les propositions`() {
        val logs = listOf(TrainingLog(scentArticle = "Gant"), TrainingLog(scentArticle = "Écharpe"), TrainingLog(scentArticle = "gant "), null)
        assertEquals(
            listOf("Gant", "Écharpe", "T-shirt", "Chaussette", "Compresse", "Objet personnel"),
            logSuggestions(logs, { it.scentArticle }, SCENT_ARTICLE_SUGGESTIONS),
        )
        assertEquals(listOf("Julie"), logSuggestions(listOf(TrainingLog(runner = "Julie"), TrainingLog()), { it.runner }))
    }
}
