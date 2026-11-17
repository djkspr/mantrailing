package app.mantrailing.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * De bout en bout : client Kotlin, vrai serveur Node et vraie base MariaDB, avec le moteur de synchronisation.
 * Ignoré sauf si MANTRAILING_TEST_SERVER donne l'adresse du serveur de développement
 * (server/scripts/dev-server.js, connexion Google simulée).
 */
class CloudServerIntegrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val url: String? = System.getenv("MANTRAILING_TEST_SERVER")
    private val run = System.nanoTime().toString()

    @Before
    fun requireServer() = assumeTrue("MANTRAILING_TEST_SERVER absent : test de bout en bout ignoré", url != null)

    private suspend fun signIn(who: String, name: String) =
        CloudApi(url!!).signInWithGoogle("google:$who-$run:$name").let { CloudApi(url, it.token) to it.user }

    /** Un téléphone : ses éléments, et un journal par espace. */
    private inner class Phone(private val api: CloudApi) {
        val data = mutableMapOf<String?, MutableMap<String, SyncItem>>()
        private val dir = tmp.newFolder()

        fun store(space: String?) = object : LocalSyncStore {
            val items = data.getOrPut(space) { linkedMapOf() }
            override suspend fun items() = items.values.toList()
            override suspend fun apply(item: SyncItem) {
                if (item.deleted) items.remove(item.key) else items[item.key] = item
            }
        }

        fun journal(space: String?) = SyncJournal(java.io.File(dir, "${space ?: "perso"}.json"))
        suspend fun sync(space: String?) = SyncEngine(store(space), api.backend(space), journal(space)).sync()
        fun put(space: String?, id: String, updatedAt: Long, content: String) {
            data.getOrPut(space) { linkedMapOf() }[keyOf(SyncKind.SESSION, id)] = SyncItem(SyncKind.SESSION, id, updatedAt, json = content)
        }
        fun content(space: String?, id: String) = data[space]?.get(keyOf(SyncKind.SESSION, id))?.json
    }

    @Test
    fun `deux telephones, un espace perso et une equipe`() = runTest {
        val (alice, aliceUser) = signIn("alice", "Alice")
        val (bob, _) = signIn("bob", "Bob")
        assertEquals("Alice", aliceUser.name)

        // Espace perso d'Alice sur deux téléphones.
        val phone1 = Phone(alice)
        val phone2 = Phone(alice)
        phone1.put(null, "s1", 100, """{"trace":"é"}""")
        assertEquals(1, phone1.sync(null).sent)
        assertEquals(1, phone2.sync(null).received)
        assertEquals("""{"trace":"é"}""", phone2.content(null, "s1"))

        // Modification sur le second, suppression sur le premier ensuite : la suppression l'emporte.
        phone2.put(null, "s1", 200, "modifiée")
        phone2.sync(null)
        phone1.data[null]!!.remove(keyOf(SyncKind.SESSION, "s1"))
        phone1.journal(null).recordDeletion(SyncKind.SESSION, "s1", 300)
        phone1.sync(null)
        phone2.sync(null)
        assertNull(phone2.content(null, "s1"))

        // Équipe : Bob rejoint avec le code, reçoit ce qui est fait dans l'équipe, jamais le perso d'Alice.
        val team = alice.createTeam("Club $run")
        val code = alice.createInvite(team.id)
        assertEquals(team.id, bob.join(InviteCodes.format(code).lowercase()).id)
        assertEquals(setOf("Alice", "Bob"), bob.me().teams.single { it.id == team.id }.members.values.toSet())
        phone1.put(team.id, "club-1", 100, "piste du club")
        phone1.put(null, "perso-2", 100, "privée")
        phone1.sync(team.id)
        phone1.sync(null)

        val bobPhone = Phone(bob)
        bobPhone.sync(team.id)
        bobPhone.sync(null)
        assertEquals("piste du club", bobPhone.content(team.id, "club-1"))
        assertTrue(bobPhone.data[null].orEmpty().isEmpty())
        assertNull(bob.backend(null).get(SyncKind.SESSION, "perso-2"))
        assertEquals("privée", alice.backend(null).get(SyncKind.SESSION, "perso-2")?.json)

        // Bob quitte l'équipe : plus d'accès.
        bob.leave(team.id)
        try {
            bob.backend(team.id).pull(null)
            throw AssertionError("accès refusé attendu")
        } catch (e: CloudException) {
            assertEquals(404, e.status)
        }

        // Suppression du compte d'Alice : son jeton ne sert plus.
        alice.deleteAccount()
        try {
            alice.me()
            throw AssertionError("jeton refusé attendu")
        } catch (e: CloudUnauthorizedException) {
            // attendu
        }
    }
}
