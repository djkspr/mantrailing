package app.mantrailing.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import app.mantrailing.session.NoteCategory
import app.mantrailing.session.SearchNote
import app.mantrailing.tractive.Position
import app.mantrailing.ui.MAP_STYLE_JSON
import app.mantrailing.ui.TRAIL_COLOR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.max

/** Ce que la carte du rapport montre : la trace du chien, la piste du traceur, les notes placées. */
data class ReportMap(
    val dog: List<List<Position>> = emptyList(),
    val trail: List<Position> = emptyList(),
    val notes: List<SearchNote> = emptyList(),
) {
    val isEmpty: Boolean get() = dog.all { it.isEmpty() } && trail.isEmpty()
}

private const val MAP_WIDTH = 1500
private const val MAP_HEIGHT = 1000
private const val DOG_COLOR = "#D9480F"

/**
 * Image de la carte : fond IGN rendu hors écran, traces dessinées par-dessus. Sans réseau (ou si le fond ne vient pas
 * à temps), les traces sont dessinées sur fond uni : le rapport reste lisible.
 */
suspend fun renderReportMap(context: Context, map: ReportMap): Bitmap? {
    if (map.isEmpty) return null
    val all = map.dog.flatten() + map.trail
    val notePoints = map.notes.mapNotNull { n -> n.lat?.let { lat -> n.lon?.let { lon -> Position(n.time, lat, lon) } } }
    val snapshot = withContext(Dispatchers.Main) {
        withTimeoutOrNull(20_000) { snapshot(context, all, notePoints) }
    }
    return withContext(Dispatchers.Default) {
        val bitmap = snapshot?.first ?: createBitmap(MAP_WIDTH, MAP_HEIGHT).apply { eraseColor("#F2EFE9".toColorInt()) }
        val project = snapshot?.second ?: plainProjection(all)
        drawOverlay(Canvas(bitmap), map, project)
        bitmap
    }
}

private suspend fun snapshot(context: Context, points: List<Position>, extra: List<Position>): Pair<Bitmap, (Position) -> PointF>? =
    suspendCancellableCoroutine { cont ->
        MapLibre.getInstance(context)
        val bounds = if (points.size == 1) {
            val p = points.first()
            LatLngBounds.from(p.lat + 0.002, p.lon + 0.003, p.lat - 0.002, p.lon - 0.003)
        } else {
            LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
        }
        val options = MapSnapshotter.Options(MAP_WIDTH, MAP_HEIGHT)
            // Tuiles IGN jusqu'au niveau 18, agrandies au-delà : au niveau 19, des tuiles manquent et l'image échouerait.
            .withStyleBuilder(Style.Builder().fromJson(MAP_STYLE_JSON.replace("\"maxzoom\": 19", "\"maxzoom\": 18")))
            .withRegion(bounds)
            .withPadding(90, 90, 90, 90)
            .withPixelRatio(1f)
            .withLogo(false)
            .withAttribution(false)
        val snapshotter = MapSnapshotter(context, options)
        cont.invokeOnCancellation { snapshotter.cancel() }
        snapshotter.start(
            { shot ->
                val copy = shot.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                // Positions converties tout de suite : l'image instantanée n'est plus valable après ce rappel.
                val pixels = HashMap<Pair<Double, Double>, PointF>()
                (points + extra).forEach { pixels[it.lat to it.lon] = shot.pixelForLatLng(LatLng(it.lat, it.lon)) }
                if (cont.isActive) cont.resume(copy to { p: Position -> pixels[p.lat to p.lon] ?: PointF(-100f, -100f) })
            },
            { _ -> if (cont.isActive) cont.resume(null) },
        )
    }

/** Projection simple (équirectangulaire locale) quand le fond de carte n'est pas disponible. */
private fun plainProjection(points: List<Position>): (Position) -> PointF {
    val minLat = points.minOf { it.lat }
    val maxLat = points.maxOf { it.lat }
    val minLon = points.minOf { it.lon }
    val maxLon = points.maxOf { it.lon }
    val k = cos(Math.toRadians((minLat + maxLat) / 2))
    val w = max((maxLon - minLon) * k, 1e-6)
    val h = max(maxLat - minLat, 1e-6)
    val pad = 90f
    val scale = minOf((MAP_WIDTH - 2 * pad) / w, (MAP_HEIGHT - 2 * pad) / h).toFloat()
    val offX = (MAP_WIDTH - w * scale).toFloat() / 2
    val offY = (MAP_HEIGHT - h * scale).toFloat() / 2
    return { p -> PointF(offX + ((p.lon - minLon) * k * scale).toFloat(), offY + ((maxLat - p.lat) * scale).toFloat()) }
}

private fun drawOverlay(canvas: Canvas, map: ReportMap, project: (Position) -> PointF) {
    fun path(points: List<Position>) = Path().apply {
        points.forEachIndexed { i, p -> project(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
    }
    fun line(color: String, width: Float, dashed: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = color.toColorInt()
        if (dashed) pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    fun dot(p: Position, color: String, radius: Float, stroke: String = "#FFFFFF") {
        val c = project(p)
        canvas.drawCircle(c.x, c.y, radius + 4, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = stroke.toColorInt() })
        canvas.drawCircle(c.x, c.y, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toColorInt() })
    }

    // Même code couleur que dans l'appli : piste bleue pointillée, trace du chien orange.
    if (map.trail.size > 1) {
        canvas.drawPath(path(map.trail), line("#FFFFFF", 12f))
        canvas.drawPath(path(map.trail), line(TRAIL_COLOR, 7f, dashed = true))
    }
    map.dog.filter { it.size > 1 }.forEach {
        canvas.drawPath(path(it), line("#FFFFFF", 12f))
        canvas.drawPath(path(it), line(DOG_COLOR, 7f))
    }
    map.trail.firstOrNull()?.let { dot(it, "#2B8A3E", 13f) }
    map.trail.lastOrNull()?.let { dot(it, "#C92A2A", 13f) }
    // Notes numérotées comme dans la liste du rapport.
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    map.notes.forEachIndexed { i, note ->
        val lat = note.lat ?: return@forEachIndexed
        val lon = note.lon ?: return@forEachIndexed
        val p = Position(note.time, lat, lon)
        val stroke = if (note.type.category == NoteCategory.TRAILER) "#FCC419" else "#FFFFFF"
        dot(p, note.type.category.color, 17f, stroke)
        val c = project(p)
        canvas.drawText("${i + 1}", c.x, c.y + 8, label)
    }
}

/** Légende de la carte, selon ce qu'elle montre. */
fun ReportMap.caption(): String = buildList {
    if (trail.isNotEmpty()) add("piste du traceur en bleu pointillé (départ vert, arrivée rouge)")
    if (dog.any { it.isNotEmpty() }) add("trace du chien en orange")
    if (notes.any { it.lat != null }) add("notes numérotées comme dans la liste")
}.joinToString(", ").replaceFirstChar { it.uppercase() } + ". Fond de carte © IGN."

// ---------- Mise en page A4 ----------

private const val PAGE_W = 595
private const val PAGE_H = 842
private const val MARGIN = 40f

/** Écrit le rapport en PDF (A4) dans [file] : en-tête, carte, rubriques, puis la liste des notes sur autant de pages qu'il faut. */
fun writeReportPdf(report: Report, mapImage: Bitmap?, file: File, notesOnMap: List<SearchNote> = emptyList(), mapCaption: String? = null) {
    val numbers = notesOnMap.withIndex().associate { (i, n) -> n.id to i + 1 }
    val doc = PdfDocument()
    var pageNumber = 0
    lateinit var page: PdfDocument.Page
    lateinit var canvas: Canvas
    var y = 0f
    val width = PAGE_W - 2 * MARGIN

    val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; color = "#212529".toColorInt() }
    val subtitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = "#495057".toColorInt() }
    val heading = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; color = "#D9480F".toColorInt() }
    val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = "#495057".toColorInt() }
    val value = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = "#212529".toColorInt() }
    val footer = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = "#868E96".toColorInt() }
    val rule = Paint().apply { color = "#DEE2E6".toColorInt(); strokeWidth = 0.8f }

    fun finishPage() {
        canvas.drawText("Mantrailing · ${report.title} · page $pageNumber", MARGIN, PAGE_H - 22f, footer)
        doc.finishPage(page)
    }
    fun newPage() {
        if (pageNumber > 0) finishPage()
        pageNumber++
        page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
        canvas = page.canvas
        y = MARGIN
    }
    fun layout(text: String, paint: TextPaint, w: Float) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, w.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    fun ensure(height: Float) {
        if (y + height > PAGE_H - 50f) newPage()
    }
    fun draw(l: StaticLayout, x: Float) {
        canvas.save()
        canvas.translate(x, y)
        l.draw(canvas)
        canvas.restore()
    }
    fun text(text: String, paint: TextPaint, gapAfter: Float = 4f) {
        val l = layout(text, paint, width)
        ensure(l.height.toFloat())
        draw(l, MARGIN)
        y += l.height + gapAfter
    }
    fun section(name: String) {
        // Titre jamais seul en bas de page : gardé avec ses premières lignes.
        ensure(80f)
        y += 8f
        text(name, heading, 2f)
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
        y += 6f
    }
    /** Deux colonnes : libellé à gauche, valeur à droite (sur plusieurs lignes si besoin). */
    fun row(left: String, right: String, leftWidth: Float, warn: Boolean = false) {
        val l = layout(left, label, leftWidth - 8f)
        val r = layout(right, if (warn) TextPaint(value).apply { color = "#C92A2A".toColorInt() } else value, width - leftWidth)
        val h = max(l.height, r.height).toFloat()
        ensure(h)
        draw(l, MARGIN)
        draw(r, MARGIN + leftWidth)
        y += h + 3f
    }

    newPage()
    text(report.title, title, 2f)
    text(report.subtitle, subtitle, 10f)

    mapImage?.let { bmp ->
        val h = width * bmp.height / bmp.width
        ensure(h)
        canvas.drawBitmap(bmp, null, RectF(MARGIN, y, MARGIN + width, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
        y += h + 4f
        text(mapCaption ?: "", footer, 6f)
    }

    report.sections.forEach { s ->
        section(s.title)
        s.rows.forEach { row(it.label, it.value, 170f) }
    }

    // Numéros identiques à ceux de la carte.
    fun numbered(list: List<ReportNote>, title: String) {
        if (list.isEmpty()) return
        section(title)
        list.forEach { n ->
            row((numbers[n.id]?.let { "$it.  " } ?: "") + n.offset, "${n.category} — ${n.text}", 120f, warn = n.warning)
        }
    }
    numbered(report.trailerNotes, "Signalements du traceur")
    numbered(report.notes, "Notes de la recherche")

    finishPage()
    file.parentFile?.mkdirs()
    file.outputStream().use { doc.writeTo(it) }
    doc.close()
}

/** Dossier des rapports, partagés par le FileProvider de l'appli (vidé à chaque export). */
fun reportsDir(context: Context) = File(context.cacheDir, "reports")

/** Propose d'envoyer ou d'enregistrer le PDF (Drive, e-mail, Fichiers…). */
fun shareReport(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension.replace('_', ' '))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Exporter le rapport").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
