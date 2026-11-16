package app.mantrailing.tractive

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class TractiveApiTest {
    private val server = MockWebServer()
    private lateinit var api: TractiveApi
    private val session = Session("user1", "tok", 2_000_000_000)

    @Before
    fun setUp() {
        server.start()
        api = TractiveApi(baseUrl = server.url("/4/"))
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `login envoie les identifiants et renvoie la session`() = runTest {
        respond("""{"user_id":"u42","client_id":"c","expires_at":1800000000,"access_token":"abc"}""")

        val s = api.login("moi@example.com", "secret")

        assertEquals(Session("u42", "abc", 1_800_000_000), s)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/4/auth/token", req.url.encodedPath)
        assertEquals(TractiveApi.CLIENT_ID, req.headers["x-tractive-client"])
        val body = req.body!!.utf8()
        assertTrue(body, body.contains("\"platform_email\":\"moi@example.com\""))
        assertTrue(body, body.contains("\"platform_token\":\"secret\""))
        assertTrue(body, body.contains("\"grant_type\":\"tractive\""))
    }

    @Test(expected = TractiveAuthException::class)
    fun `login refusé lève une erreur d'authentification`() = runTest {
        respond("""{"code":4002,"message":"invalid credentials"}""", code = 401)
        api.login("moi@example.com", "faux")
    }

    @Test
    fun `pets récupère nom et collier de chaque animal, avec le jeton`() = runTest {
        respond("""[{"_id":"p1","_type":"pet_type"}]""")
        respond("""{"_id":"p1","device_id":"TRK1","details":{"name":"Rex","pet_type":"DOG"},"extra":1}""")

        val pets = api.pets(session)

        assertEquals(listOf(Pet("p1", "Rex", "TRK1")), pets)
        val first = server.takeRequest()
        assertEquals("/4/user/user1/trackable_objects", first.url.encodedPath)
        assertEquals("Bearer tok", first.headers["authorization"])
        assertEquals("user1", first.headers["x-tractive-user"])
        assertEquals("/4/trackable_object/p1", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `positions interroge la bonne plage et lit les segments`() = runTest {
        respond(
            """[[
              {"time":1000,"latlong":[44.1,2.1],"alt":239,"speed":null,"course":null,"pos_uncertainty":30,"sensor_used":"KNOWN_WIFI"},
              {"time":1150,"latlong":[44.2,2.2],"alt":null,"pos_uncertainty":2,"sensor_used":"GPS"}
            ],[
              {"time":5000,"latlong":[44.3,2.3]}
            ]]""",
        )

        val segments = api.positions(session, "TRK1", Instant.ofEpochSecond(900), Instant.ofEpochSecond(6000))

        assertEquals(2, segments.size)
        assertEquals(Position(1000, 44.1, 2.1, 239.0, 30.0, "KNOWN_WIFI"), segments[0][0])
        assertEquals("GPS", segments[0][1].sensor)
        assertNull(segments[1][0].uncertainty)
        val url = server.takeRequest().url
        assertEquals("/4/tracker/TRK1/positions", url.encodedPath)
        assertEquals("900", url.queryParameter("time_from"))
        assertEquals("6000", url.queryParameter("time_to"))
        assertEquals("json_segments", url.queryParameter("format"))
    }

    @Test
    fun `positions vides ou sans coordonnées sont ignorées`() = runTest {
        respond("""[[],[{"time":1}],[{"time":3,"latlong":[1.0,2.0]},{"time":2,"latlong":[1.5,2.5]}]]""")

        val segments = api.positions(session, "T", Instant.EPOCH, Instant.ofEpochSecond(10))

        assertEquals(1, segments.size)
        assertEquals(listOf(2L, 3L), segments[0].map { it.time }) // remis dans l'ordre chronologique
    }

    @Test
    fun `parseSegments accepte une liste de points à plat`() {
        val json = TractiveApi.json.parseToJsonElement("""[{"time":1,"latlong":[1.0,2.0]},{"time":2,"latlong":[1.1,2.1]}]""")
        assertEquals(1, TractiveApi.parseSegments(json).size)
        assertEquals(2, TractiveApi.parseSegments(json)[0].size)
    }

    @Test
    fun `mode LIVE envoie la commande au collier`() = runTest {
        respond("""{"pending":true}""")
        respond("")

        api.setLiveTracking(session, "TRK1", true)
        api.setLiveTracking(session, "TRK1", false)

        assertEquals("/4/tracker/TRK1/command/live_tracking/on", server.takeRequest().url.encodedPath)
        assertEquals("/4/tracker/TRK1/command/live_tracking/off", server.takeRequest().url.encodedPath)
    }

    @Test(expected = TractiveException::class)
    fun `erreur serveur remontée en TractiveException`() = runTest {
        respond("boom", code = 500)
        api.pets(session)
    }
}
