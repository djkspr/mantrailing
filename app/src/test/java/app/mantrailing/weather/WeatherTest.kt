package app.mantrailing.weather

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class WeatherTest {
    private val server = MockWebServer()
    private lateinit var api: WeatherApi

    @Before
    fun setUp() {
        server.start()
        api = WeatherApi(baseUrl = server.url("/v1/forecast"))
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    // 17/11/2026 : 09:00 et 10:00 UTC.
    private val nine = 1_794_906_000L
    private val ten = nine + 3_600

    private val hourly = """
        {"hourly":{"time":[$nine,$ten],
          "temperature_2m":[8.4,9.6],"relative_humidity_2m":[88,81],"precipitation":[0.0,0.4],"cloud_cover":[95,100],
          "wind_speed_10m":[11.2,14.8],"wind_direction_10m":[300,315],"wind_gusts_10m":[20.0,31.0]}}
    """.trimIndent()

    @Test
    fun `meteo de l'heure la plus proche, position arrondie a 1 km`() = runTest {
        respond(hourly)

        val weather = api.at(48.425437, 2.640212, ten - 600)!!

        assertEquals(Weather(ten, 9.6, 14.8, 315, 31.0, 81, 0.4, 100), weather)
        val url = server.takeRequest().url
        assertEquals("48.43", url.queryParameter("latitude"))
        assertEquals("2.64", url.queryParameter("longitude"))
        assertEquals("2026-11-17", url.queryParameter("start_date"))
        assertEquals("unixtime", url.queryParameter("timeformat"))
    }

    @Test
    fun `resume lisible`() {
        assertEquals(
            "10 °C · vent de nord-ouest 15 km/h (rafales 31) · humidité 81 % · pluie 0,4 mm",
            Weather(ten, 9.6, 14.8, 315, 31.0, 81, 0.4, 100).summary,
        )
        // Rafales proches du vent moyen : pas mentionnées ; sans pluie, l'état du ciel.
        assertEquals("8 °C · vent d'ouest 11 km/h · humidité 88 % · couvert", Weather(nine, 8.4, 11.2, 270, 15.0, 88, 0.0, 95).summary)
        assertEquals("8 °C, vent d'ouest 11 km/h", Weather(nine, 8.4, 11.2, 270).short)
    }

    @Test
    fun `direction d'ou vient le vent`() {
        assertEquals("du nord", windFrom(0))
        assertEquals("du nord", windFrom(350))
        assertEquals("de nord-est", windFrom(40))
        assertEquals("d'est", windFrom(90))
        assertEquals("du sud", windFrom(185))
        assertEquals("de sud-ouest", windFrom(225))
        assertEquals("de nord-ouest", windFrom(-45))
    }

    @Test
    fun `indisponible - serveur en erreur, reponse illisible, heure trop eloignee`() = runTest {
        respond("""{"error":true}""", 400)
        assertNull(api.at(48.0, 2.0, ten))
        respond("pas du json")
        assertNull(api.at(48.0, 2.0, ten))
        respond(hourly)
        assertNull(api.at(48.0, 2.0, ten + 5 * 3_600))
    }
}
