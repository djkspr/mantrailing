package app.mantrailing.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Serveur en mémoire : chaque écriture reçoit un numéro croissant, qui sert de curseur. */
private class FakeBackend : SyncBackend {
    val docs = linkedMapOf<String, Pair<SyncItem, Long>>()
    private var seq = 0L
    var failPush = false
    var pushes = 0

    override suspend fun push(items: List<SyncItem>) {
        if (failPush) throw IOException("réseau coupé")
        pushes++
        items.forEach { docs[it.key] = it to ++seq }
    }

    override suspend fun pull(cursor: String?): PullResult {
        val from = cursor?.toLong() ?: 0
        val newer = docs.values.filter { it.second > from }.sortedBy { it.second }
        return PullResult(newer.map { it.first }, newer.lastOrNull()?.second?.toString() ?: cursor)
    }
}

/** Un téléphone : ses éléments, son journal de synchronisation. */
private class Phone(dir: java.io.File, private val backend: FakeBackend) : LocalSyncStore {
    val data = linkedMapOf<String, SyncItem>()
    val journal = SyncJournal(java.io.File(dir, "state.json"))
    private val engine = SyncEngine(this, backend, journal, now = { 1L })

    override suspend fun items() = data.values.toList()
    override suspend fun apply(item: SyncItem) {
        if (item.deleted) data.remove(item.key) else data[item.key] = item
    }

    fun put(id: String, updatedAt: Long, content: String = "v$updatedAt", kind: SyncKind = SyncKind.SESSION) {
        data[keyOf(kind, id)] = SyncItem(kind, id, updatedAt, json = content)
    }

    suspend fun delete(id: String, at: Long, kind: SyncKind = SyncKind.SESSION) {
        data.remove(keyOf(kind, id))
        journal.recordDeletion(kind, id, at)
    }

    suspend fun sync() = engine.sync()
    fun content(id: String) = data[keyOf(SyncKind.SESSION, id)]?.json
}

class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = FakeBackend()
    private fun phone() = Phone(tmp.newFolder(), server)

    @Test
    fun `premiere synchronisation envoie tout, la suivante rien`() = runTest {
        val a = phone()
        a.put("s1", 100)
        a.put("d1", 100, kind = SyncKind.DOG)

        assertEquals(SyncReport(sent = 2, received = 0), a.sync())
        assertEquals(setOf("SESSION_s1", "DOG_d1"), server.docs.keys)
        assertEquals(SyncReport(sent = 0, received = 0), a.sync())
    }

    @Test
    fun `un deuxieme telephone recoit les donnees du premier`() = runTest {
        val a = phone()
        val b = phone()
        a.put("s1", 100)
        a.sync()

        assertEquals(SyncReport(sent = 0, received = 1), b.sync())
        assertEquals("v100", b.content("s1"))
        // Ce qu'il vient de recevoir n'est pas renvoyé.
        assertEquals(0, b.sync().sent)
    }

    @Test
    fun `la modification la plus recente l'emporte des deux cotes`() = runTest {
        val a = phone()
        val b = phone()
        a.put("s1", 100)
        a.sync()
        b.sync()

        a.put("s1", 300, "notes de A")
        b.put("s1", 200, "notes de B")
        a.sync()
        b.sync() // reçoit la version de A, plus récente : la sienne n'est pas envoyée
        a.sync()

        assertEquals("notes de A", a.content("s1"))
        assertEquals("notes de A", b.content("s1"))
        assertEquals("notes de A", server.docs["SESSION_s1"]!!.first.json)
    }

    @Test
    fun `une version locale plus recente n'est pas ecrasee et part au serveur`() = runTest {
        val a = phone()
        val b = phone()
        a.put("s1", 100)
        a.sync()
        b.sync()
        b.put("s1", 500, "corrigé sur B")
        a.put("s1", 200, "corrigé sur A")
        a.sync()

        b.sync()
        a.sync()

        assertEquals("corrigé sur B", a.content("s1"))
        assertEquals("corrigé sur B", b.content("s1"))
    }

    @Test
    fun `une suppression passe aux autres telephones`() = runTest {
        val a = phone()
        val b = phone()
        a.put("s1", 100)
        a.put("s2", 100)
        a.sync()
        b.sync()

        a.delete("s1", at = 200)
        assertEquals(1, a.sync().sent)
        assertTrue(a.journal.read().tombstones.isEmpty())
        assertEquals(true, server.docs["SESSION_s1"]!!.first.deleted)

        b.sync()
        assertNull(b.content("s1"))
        assertEquals("v100", b.content("s2"))
    }

    @Test
    fun `modification ailleurs apres une suppression ici - la modification l'emporte`() = runTest {
        val a = phone()
        val b = phone()
        a.put("s1", 100)
        a.sync()
        b.sync()

        a.delete("s1", at = 200)
        b.put("s1", 300, "complété sur B")
        b.sync()
        a.sync()

        assertEquals("complété sur B", a.content("s1"))
        assertTrue(a.journal.read().tombstones.isEmpty())
        assertEquals(false, server.docs["SESSION_s1"]!!.first.deleted)
    }

    @Test
    fun `suppression ici apres une modification ailleurs - la suppression l'emporte`() = runTest {
        val a = phone()
        val b = phone()
        a.put("s1", 100)
        a.sync()
        b.sync()

        b.put("s1", 200, "complété sur B")
        b.sync()
        a.delete("s1", at = 300)
        a.sync()
        b.sync()

        assertNull(a.content("s1"))
        assertNull(b.content("s1"))
    }

    @Test
    fun `echec reseau - rien n'est perdu, tout repart au prochain essai`() = runTest {
        val a = phone()
        a.put("s1", 100)
        a.delete("s0", at = 50)
        server.failPush = true
        try {
            a.sync()
            fail("l'échec doit remonter pour que WorkManager réessaie")
        } catch (e: IOException) {
            // attendu
        }
        assertTrue(server.docs.isEmpty())

        server.failPush = false
        assertEquals(2, a.sync().sent)
        assertEquals(setOf("SESSION_s1", "SESSION_s0"), server.docs.keys)
    }

    @Test
    fun `un telephone neuf recoit tout, suppressions ignorees`() = runTest {
        val a = phone()
        a.put("s1", 100)
        a.put("s2", 100)
        a.sync()
        a.delete("s2", at = 200)
        a.sync()

        val neuf = phone()
        assertEquals(SyncReport(sent = 0, received = 1), neuf.sync())
        assertEquals(setOf("SESSION_s1"), neuf.data.keys)
    }

    @Test
    fun `le journal survit a un redemarrage et se remet a zero a la deconnexion`() = runTest {
        val dir = tmp.newFolder()
        val a = Phone(dir, server)
        a.put("s1", 100)
        a.sync()

        val relaunched = Phone(dir, server).apply { put("s1", 100) }
        assertEquals(0, relaunched.sync().sent)

        relaunched.journal.reset()
        assertEquals(1, relaunched.sync().sent)
    }

    @Test
    fun `lots d'ecriture Firestore limites en nombre et en taille`() {
        val small = (1..450).map { SyncItem(SyncKind.SESSION, "s$it", 1, json = "x") }
        assertEquals(listOf(400, 50), ApiBackend.chunked(small).map { it.size })

        val big = (1..5).map { SyncItem(SyncKind.SESSION, "b$it", 1, json = "x".repeat(900_000)) }
        assertEquals(listOf(4, 1), ApiBackend.chunked(big).map { it.size })
        // Un élément seul plus gros que la limite part quand même, dans son propre lot.
        val huge = listOf(SyncItem(SyncKind.SESSION, "h", 1, json = "x".repeat(5_000_000)))
        assertEquals(listOf(1), ApiBackend.chunked(huge).map { it.size })
    }
}
