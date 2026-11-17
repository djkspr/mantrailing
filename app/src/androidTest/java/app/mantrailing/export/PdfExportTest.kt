package app.mantrailing.export

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mantrailing.session.NoteType
import app.mantrailing.session.SearchNote
import app.mantrailing.session.TrackSource
import app.mantrailing.session.TrailSession
import app.mantrailing.tractive.Position
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val start = 1_794_906_000L
    private val trail = (0..20).map { Position(start - 1_800 + it * 30L, 48.0 + it * 0.0001, 2.0) }
    private val dog = (0..20).map { Position(start + it * 10L, 48.0 + it * 0.0001, 2.0001) }

    private fun session(noteCount: Int) = TrailSession(
        "s1", "Rex", TrackSource.PHONE, start, start + 600, listOf(dog), trail = trail, trailEndedAt = start - 1_200,
        notes = (1..noteCount).map { SearchNote("n$it", start + it * 5L, NoteType.HESITATION, "note numéro $it", lat = 48.0 + it * 0.00002, lon = 2.0) },
    )

    private fun pages(file: File) = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }

    @Test
    fun rapport_lisible_avec_carte() = runBlocking {
        val s = session(3)
        val map = ReportMap(s.segments, s.trail, s.notes)
        // Fond IGN si le réseau répond, sinon fond uni : une image dans tous les cas.
        val image = renderReportMap(context, map)
        assertNotNull(image)
        assertEquals(1500, image!!.width)

        val file = File(reportsDir(context), "test.pdf")
        writeReportPdf(sessionReport(s, "Perso"), image, file, map.notes)
        assertTrue(file.length() > 1_000)
        assertEquals("%PDF", file.readBytes().copyOf(4).decodeToString())
        assertTrue(pages(file) in 1..2)
        // Aperçu des pages (à récupérer avec adb pour vérifier la mise en page à l'oeil).
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
            repeat(pdf.pageCount) { i ->
                pdf.openPage(i).use { page ->
                    val bmp = android.graphics.Bitmap.createBitmap(page.width * 2, page.height * 2, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    File(context.cacheDir, "apercu-${i + 1}.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }

    @Test
    fun beaucoup_de_notes_sur_plusieurs_pages() {
        val s = session(120)
        val file = File(reportsDir(context), "long.pdf")
        writeReportPdf(sessionReport(s, "Perso"), null, file, s.notes)
        assertTrue(pages(file) >= 3)
    }

    @Test
    fun rien_a_dessiner_pas_de_carte() = runBlocking {
        assertEquals(null, renderReportMap(context, ReportMap()))
    }
}
