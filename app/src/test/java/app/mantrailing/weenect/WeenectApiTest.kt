package app.mantrailing.weenect

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class WeenectApiTest {
    private val server = MockWebServer()
    private lateinit var api: WeenectApi
    private val session = WeenectSession("abc.def")

    @Before
    fun setUp() {
        server.start()
        api = WeenectApi(baseUrl = server.url("/v4/"))
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `connexion - identifiants envoyes, jeton garde`() = runTest {
        respond("""{"access_token":"abc.def","expires_in":3600}""")
        assertEquals(session, api.login("moi@example.com", "secret"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v4/user/login", request.url.encodedPath)
        assertEquals("""{"username":"moi@example.com","password":"secret"}""", request.body?.utf8())
    }

    @Test
    fun `traceurs du compte, jeton JWT dans l'en-tete`() = runTest {
        respond("""{"items":[{"id":100000,"name":"Rex","type":"pet2","position":[]},{"id":"200","name":""}],"total":2}""")
        assertEquals(listOf(WeenectTracker("100000", "Rex"), WeenectTracker("200", "Traceur 200")), api.trackers(session))
        val request = server.takeRequest()
        assertEquals("/v4/mytracker", request.url.encodedPath)
        assertEquals("JWT abc.def", request.headers["authorization"])
    }

    @Test
    fun `historique - periode demandee, positions GPS gardees, antennes imprecises ecartees`() = runTest {
        respond(
            """[
              {"latitude":48.4255,"longitude":2.6403,"date_tracker":"2026-11-17T09:00:05+00:00","radius":8,"satellites":7,"valid_signal":true},
              {"latitude":48.4254,"longitude":2.6402,"date_tracker":"2026-11-17T09:00:00+00:00","radius":10,"satellites":6,"valid_signal":true},
              {"latitude":48.5,"longitude":2.7,"date_tracker":"2026-11-17T09:00:10+00:00","radius":1200,"satellites":0,"valid_signal":false},
              {"latitude":48.4256,"longitude":2.6404,"date_tracker":"2026-11-17T09:00:15+00:00","radius":30,"satellites":0},
              {"latitude":48.4,"longitude":2.6,"date_tracker":"2026-11-17T12:00:00+00:00","radius":5,"satellites":5}
            ]""",
        )
        val from = Instant.ofEpochSecond(1_794_906_000)
        val positions = api.positions(session, "100000", from, from.plusSeconds(600))

        assertEquals(listOf(1_794_906_000L, 1_794_906_005L, 1_794_906_015L), positions.map { it.time })
        assertEquals(listOf(WEENECT_GPS_SENSOR, WEENECT_GPS_SENSOR, WEENECT_CELL_SENSOR), positions.map { it.sensor })
        assertEquals(10.0, positions[0].uncertainty!!, 0.0)
        val url = server.takeRequest().url
        assertEquals("/v4/mytracker/100000/position", url.encodedPath)
        assertEquals("2026-11-17T09:00:00Z", url.queryParameter("start"))
        assertEquals("2026-11-17T09:10:00Z", url.queryParameter("end"))
    }

    @Test
    fun `historique dans un objet items accepte`() {
        val positions = WeenectApi.parsePositions(
            WeenectApi.json.parseToJsonElement(
                """{"items":[{"latitude":48.0,"longitude":2.0,"date_tracker":"2026-11-17T09:00:00+00:00","satellites":4}]}""",
            ),
        )
        assertEquals(1, positions.size)
    }

    @Test
    fun `super live demande au traceur`() = runTest {
        respond("")
        api.superLive(session, "100000")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v4/mytracker/100000/st-mode", request.url.encodedPath)
    }

    @Test
    fun `session refusee - il faut se reconnecter`() = runTest {
        respond("""{"error":"invalid token"}""", 401)
        val error = runCatching { api.trackers(session) }.exceptionOrNull()
        assertTrue(error is WeenectAuthException)
        respond("oups", 500)
        assertThrows(WeenectException::class.java) { kotlinx.coroutines.runBlocking { api.trackers(session) } }
    }
}
