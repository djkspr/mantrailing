package app.mantrailing.sync

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Client de l'API de sauvegarde, contre un faux serveur. */
class CloudApiTest {
    private val server = MockWebServer()
    private lateinit var api: CloudApi

    @Before
    fun setUp() {
        server.start()
        // Adresse avec un chemin, comme derrière un proxy : les routes s'y ajoutent.
        api = CloudApi(server.url("/api/").toString(), token = "jeton-appli")
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `connexion Google - jeton Google envoye, jeton de l'appli recu`() = runTest {
        respond("""{"token":"t-123","user":{"id":"7","email":"moi@exemple.fr","name":"Moi"}}""")

        val result = CloudApi(server.url("/api").toString()).signInWithGoogle("""jeton"google""")

        assertEquals(SignInResult("t-123", CloudUser("7", "moi@exemple.fr", "Moi")), result)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/auth/google", req.url.encodedPath)
        assertNull(req.headers["Authorization"])
        assertEquals("""{"idToken":"jeton\"google"}""", req.body!!.utf8())
    }

    @Test
    fun `profil et equipes`() = runTest {
        respond(
            """{"user":{"id":"7","name":"Moi"},"teams":[{"id":"abc","name":"Famille","ownerId":"7","members":{"7":"Moi","9":"Claire"}}]}""",
        )

        val me = api.me()

        assertEquals(listOf(Team("abc", "Famille", "7", mapOf("7" to "Moi", "9" to "Claire"))), me.teams)
        assertTrue(me.teams.single().shared)
        assertEquals("Bearer jeton-appli", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `envoi des elements d'une equipe`() = runTest {
        respond("""{"received":2}""")

        api.backend("abc").push(
            listOf(
                SyncItem(SyncKind.SESSION, "s1", 100, json = """{"id":"s1"}"""),
                SyncItem(SyncKind.DOG, "d1", 200, deleted = true),
            ),
        )

        val req = server.takeRequest()
        assertEquals("/api/spaces/abc/items", req.url.encodedPath)
        assertEquals(
            """{"items":[{"kind":"SESSION","id":"s1","updatedAt":100,"deleted":false,"json":"{\"id\":\"s1\"}"},""" +
                """{"kind":"DOG","id":"d1","updatedAt":200,"deleted":true,"json":null}]}""",
            req.body!!.utf8(),
        )
    }

    @Test
    fun `reception par pages jusqu'au bout, curseur conserve`() = runTest {
        respond("""{"items":[{"kind":"SESSION","id":"s1","updatedAt":1,"deleted":false,"json":"{}"}],"cursor":5,"more":true}""")
        respond("""{"items":[{"kind":"TRAIL","id":"t1","updatedAt":2,"deleted":true,"json":null},{"kind":"AUTRE","id":"x","updatedAt":3,"deleted":false}],"cursor":9,"more":false}""")

        val result = api.backend(null).pull("4")

        assertEquals(listOf("s1", "t1"), result.items.map { it.id }) // type inconnu (version future) ignoré
        assertTrue(result.items[1].deleted)
        assertEquals("9", result.cursor)
        val first = server.takeRequest()
        assertEquals("/api/spaces/perso/items", first.url.encodedPath)
        assertEquals("4", first.url.queryParameter("after"))
        assertEquals("5", server.takeRequest().url.queryParameter("after"))
    }

    @Test
    fun `curseur de reception transmis tel quel`() = runTest {
        respond("""{"items":[],"cursor":12,"more":false}""")

        val result = api.backend(null).pull("12")

        assertEquals("12", result.cursor)
        val req = server.takeRequest()
        assertEquals("/api/spaces/perso/items", req.url.encodedPath)
        assertEquals("12", req.url.queryParameter("after"))
    }

    @Test
    fun `element introuvable - null, autre erreur - message du serveur`() = runTest {
        respond("""{"error":"Introuvable."}""", 404)
        assertNull(api.backend("abc").get(SyncKind.SESSION, "s1"))

        respond("""{"error":"Ce code d'invitation a expiré."}""", 410)
        try {
            api.join("ABCD-EFGH")
            fail("erreur attendue")
        } catch (e: CloudException) {
            assertEquals(410, e.status)
            assertEquals("Ce code d'invitation a expiré.", e.message)
        }
    }

    @Test
    fun `jeton refuse - il faut se reconnecter`() = runTest {
        respond("""{"error":"Connexion expirée, reconnecte-toi."}""", 401)
        try {
            api.me()
            fail("erreur attendue")
        } catch (e: CloudUnauthorizedException) {
            assertEquals("Connexion expirée, reconnecte-toi.", e.message)
        }
    }

    @Test
    fun `equipes - creation, invitation, depart`() = runTest {
        respond("""{"id":"abc","name":"Club","ownerId":"7","members":{"7":"Moi"}}""", 201)
        respond("""{"code":"K7PM4XQ2","expiresAt":"2026-11-24T10:00:00.000Z"}""", 201)
        respond("", 204)

        assertEquals("Club", api.createTeam("Club").name)
        assertEquals("K7PM4XQ2", api.createInvite("abc"))
        api.leave("abc")

        assertEquals("""{"name":"Club"}""", server.takeRequest().body!!.utf8())
        assertEquals("/api/teams/abc/invites", server.takeRequest().url.encodedPath)
        val leave = server.takeRequest()
        assertEquals("DELETE", leave.method)
        assertEquals("/api/teams/abc/members/me", leave.url.encodedPath)
    }
}
