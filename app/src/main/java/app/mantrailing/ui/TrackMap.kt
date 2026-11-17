package app.mantrailing.ui

import kotlin.math.cos
import kotlin.math.pow
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.mantrailing.session.NoteCategory
import app.mantrailing.offline.Area
import app.mantrailing.session.SearchNote
import app.mantrailing.tractive.Position
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Fonds de carte : IGN (France, gratuit sans clé) et OSM via CARTO. */
private val BASE_LAYERS = listOf("plan" to "Plan IGN", "photo" to "Photos IGN", "osm" to "OSM")

private fun ign(layer: String, format: String) =
    "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=$layer" +
        "&STYLE=normal&TILEMATRIXSET=PM&FORMAT=$format&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}"

/** Tuiles du plan IGN : même adresse pour la carte et les zones téléchargées (cartes hors ligne). */
val IGN_PLAN_TILES = ign("GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2", "image/png")

/** Style de carte (fonds IGN, OSM), aussi utilisé pour l'image des rapports PDF. */
val MAP_STYLE_JSON = """
{
  "version": 8,
  "center": [2.4, 46.6], "zoom": 5,
  "sources": {
    "plan": { "type": "raster", "tileSize": 256, "maxzoom": 19, "attribution": "© IGN",
              "tiles": ["$IGN_PLAN_TILES"] },
    "photo": { "type": "raster", "tileSize": 256, "maxzoom": 19, "attribution": "© IGN",
               "tiles": ["${ign("ORTHOIMAGERY.ORTHOPHOTOS", "image/jpeg")}"] },
    "osm": { "type": "raster", "tileSize": 256, "maxzoom": 19, "attribution": "© OpenStreetMap, © CARTO",
             "tiles": ["https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png",
                       "https://b.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png"] }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#F2EFE9" } },
    { "id": "plan", "type": "raster", "source": "plan" },
    { "id": "photo", "type": "raster", "source": "photo", "layout": { "visibility": "none" } },
    { "id": "osm", "type": "raster", "source": "osm", "layout": { "visibility": "none" } }
  ]
}
""".trimIndent()

private const val DOG_SOURCE = "dog"
private const val TRAIL_SOURCE = "trail"
private const val TRAIL_ENDS_SOURCE = "trail-ends"
private const val NOTES_SOURCE = "notes"
private const val ME_SOURCE = "me"

@Composable
/** Carte : trace du chien ([segments], orange) et piste posée par le traceur ([trail], bleu pointillé). */
fun TrackMap(
    segments: List<List<Position>>,
    modifier: Modifier = Modifier,
    trail: List<Position> = emptyList(),
    notes: List<SearchNote> = emptyList(),
    onNoteClick: (SearchNote) -> Unit = {},
    /** Pose en cours : la carte suit la dernière position, tant qu'on ne la déplace pas à la main. */
    follow: Boolean = false,
    /** Zone visible, à chaque arrêt de la carte (téléchargement pour l'usage hors ligne). */
    onVisibleArea: (Area) -> Unit = {},
    /** Mode formateur : couloir de cette largeur (mètres de part et d'autre) dessiné autour de la piste. */
    corridorMeters: Int? = null,
) {
    val currentNotes by rememberUpdatedState(notes)
    val currentOnVisibleArea by rememberUpdatedState(onVisibleArea)
    val currentOnNoteClick by rememberUpdatedState(onNoteClick)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var baseIndex by rememberSaveable { mutableIntStateOf(0) }
    // Suivi interrompu dès que l'utilisateur déplace ou zoome la carte ; « Me localiser » le reprend.
    var following by remember { mutableStateOf(true) }
    var followZoomed by remember { mutableStateOf(false) }
    LaunchedEffect(follow) {
        following = true
        followZoomed = false
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            // Vue de la France tant qu'aucune trace n'est affichée (le centre du style n'est pas appliqué).
            m.cameraPosition = CameraPosition.Builder().target(LatLng(46.6, 2.4)).zoom(4.6).build()
            m.setStyle(Style.Builder().fromJson(MAP_STYLE_JSON)) { style ->
                // Piste du traceur, sous la trace du chien : bleu pointillé, départ vert, arrivée rouge.
                style.addSource(GeoJsonSource(TRAIL_SOURCE))
                style.addSource(GeoJsonSource(TRAIL_ENDS_SOURCE))
                // Couloir du mode formateur : large bande translucide, invisible hors mode formateur.
                style.addLayer(
                    LineLayer(CORRIDOR_LAYER, TRAIL_SOURCE).withProperties(
                        PropertyFactory.lineColor(TRAIL_COLOR),
                        PropertyFactory.lineOpacity(0.18f),
                        PropertyFactory.lineWidth(0f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
                )
                style.addLayer(
                    LineLayer("trail-casing", TRAIL_SOURCE).withProperties(
                        PropertyFactory.lineColor("#FFFFFF"),
                        PropertyFactory.lineOpacity(0.85f),
                        PropertyFactory.lineWidth(7f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addLayer(
                    LineLayer("trail-line", TRAIL_SOURCE).withProperties(
                        PropertyFactory.lineColor(TRAIL_COLOR),
                        PropertyFactory.lineWidth(4f),
                        PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addSource(GeoJsonSource(DOG_SOURCE))
                // Liseré blanc sous la trace : lisible sur le plan comme sur les photos aériennes.
                style.addLayer(
                    LineLayer("dog-casing", DOG_SOURCE).withProperties(
                        PropertyFactory.lineColor("#FFFFFF"),
                        PropertyFactory.lineOpacity(0.85f),
                        PropertyFactory.lineWidth(7f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
                )
                style.addLayer(
                    LineLayer("dog-line", DOG_SOURCE).withProperties(
                        PropertyFactory.lineColor("#D9480F"),
                        PropertyFactory.lineWidth(4f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
                )
                // Points discrets en vue d'ensemble (en LIVE ils sont à quelques mètres les uns des autres),
                // bien visibles quand on zoome pour examiner un passage.
                style.addLayer(
                    CircleLayer("dog-points", DOG_SOURCE).withProperties(
                        PropertyFactory.circleRadius(
                            Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(14, 0.8f), Expression.stop(19, 5f)),
                        ),
                        PropertyFactory.circleColor("#862E00"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(
                            Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(16, 0f), Expression.stop(18, 1.5f)),
                        ),
                    ),
                )
                style.addLayer(
                    CircleLayer("trail-ends", TRAIL_ENDS_SOURCE).withProperties(
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleColor(
                            Expression.match(Expression.get("kind"), Expression.literal(TRAIL_END_COLOR), Expression.stop("start", TRAIL_START_COLOR)),
                        ),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(3f),
                    ),
                )
                // Notes du maître-chien : un repère coloré par famille, touché pour lire ou modifier la note.
                style.addSource(GeoJsonSource(NOTES_SOURCE))
                style.addLayer(
                    CircleLayer("notes", NOTES_SOURCE).withProperties(
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleColor(
                            Expression.match(
                                Expression.get("cat"), Expression.literal(NoteCategory.FREE.color),
                                *NoteCategory.entries.map { Expression.stop(it.name, it.color) }.toTypedArray(),
                            ),
                        ),
                        // Signalements du traceur cerclés de jaune, comme un panneau d'alerte.
                        PropertyFactory.circleStrokeColor(
                            Expression.match(Expression.get("cat"), Expression.literal("#FFFFFF"), Expression.stop(NoteCategory.TRAILER.name, "#FCC419")),
                        ),
                        PropertyFactory.circleStrokeWidth(3f),
                    ),
                )
                // Position du téléphone (« Me localiser ») : point bleu cerclé de blanc, au-dessus de tout.
                style.addSource(GeoJsonSource(ME_SOURCE))
                style.addLayer(
                    CircleLayer("me", ME_SOURCE).withProperties(
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleColor("#1971C2"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(3f),
                    ),
                )
                m.addOnCameraIdleListener {
                    val b = m.projection.visibleRegion.latLngBounds
                    currentOnVisibleArea(Area(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast))
                }
                m.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) following = false
                }
                m.addOnMapClickListener { latLng ->
                    val id = m.queryRenderedFeatures(m.projection.toScreenLocation(latLng), "notes")
                        .firstOrNull()?.getStringProperty("id")
                    val note = currentNotes.find { it.id == id }
                    note?.let(currentOnNoteClick)
                    note != null
                }
                map = m to style
            }
        }
    }

    LaunchedEffect(map, baseIndex) {
        val (_, style) = map ?: return@LaunchedEffect
        BASE_LAYERS.forEachIndexed { i, (id, _) ->
            style.getLayer(id)?.setProperties(PropertyFactory.visibility(if (i == baseIndex) Property.VISIBLE else Property.NONE))
        }
    }

    LaunchedEffect(map, corridorMeters, trail.firstOrNull()) {
        val (_, style) = map ?: return@LaunchedEffect
        val lat = trail.firstOrNull()?.lat
        style.getLayer(CORRIDOR_LAYER)?.setProperties(
            if (corridorMeters == null || lat == null) PropertyFactory.lineWidth(0f)
            else PropertyFactory.lineWidth(corridorWidthExpression(corridorMeters, lat)),
        )
    }

    LaunchedEffect(map, notes) {
        val (_, style) = map ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(NOTES_SOURCE)?.setGeoJson(noteFeatures(notes))
    }

    LaunchedEffect(map, segments, trail) {
        val (m, style) = map ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(DOG_SOURCE)?.setGeoJson(toFeatures(segments))
        style.getSourceAs<GeoJsonSource>(TRAIL_SOURCE)?.setGeoJson(trailLine(trail))
        style.getSourceAs<GeoJsonSource>(TRAIL_ENDS_SOURCE)?.setGeoJson(trailEnds(trail))
        val points = (segments.flatten() + trail).map { LatLng(it.lat, it.lon) }
        if (follow) {
            // Position suivie : la dernière de la trace en cours (mode formateur), sinon celle du parcours posé.
            val live = segments.lastOrNull { it.isNotEmpty() }?.last() ?: trail.lastOrNull()
            val last = live?.let { LatLng(it.lat, it.lon) } ?: return@LaunchedEffect
            if (!following) return@LaunchedEffect
            // Zoom de marche la première fois ; ensuite on garde celui choisi.
            val zoom = if (followZoomed) m.cameraPosition.zoom else 17.0
            followZoomed = true
            m.easeCamera(CameraUpdateFactory.newLatLngZoom(last, zoom), 500)
            return@LaunchedEffect
        }
        when {
            points.size == 1 -> m.easeCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 16.0))
            points.size > 1 -> m.easeCamera(
                // Marge plus grande en haut : le bouton de fond de carte ne doit pas cacher l'arrivée.
                CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 80, 220, 80, 80),
            )
        }
    }

    // « Me localiser » : la carte se centre sur le téléphone, marqué d'un point bleu.
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    fun locate() {
        locating = true
        scope.launch {
            val here = currentLocation(context)
            locating = false
            val (m, style) = map ?: return@launch
            following = true
            if (here == null) {
                Toast.makeText(context, "Position introuvable : active la localisation du téléphone.", Toast.LENGTH_LONG).show()
                return@launch
            }
            style.getSourceAs<GeoJsonSource>(ME_SOURCE)?.setGeoJson(Point.fromLngLat(here.longitude, here.latitude))
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(here.latitude, here.longitude), maxOf(m.cameraPosition.zoom, 16.0)))
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate()
        else Toast.makeText(context, "Sans autorisation de localisation, la carte ne peut pas te situer.", Toast.LENGTH_LONG).show()
    }

    Box(modifier) {
        AndroidView(factory = { mapView })
        FilledTonalButton(
            onClick = { baseIndex = (baseIndex + 1) % BASE_LAYERS.size },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) { Text(BASE_LAYERS[baseIndex].second) }
        FilledTonalButton(
            onClick = {
                val allowed = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                if (allowed) locate()
                else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            enabled = !locating,
            // Au-dessus de l'attribution des fonds de carte, en bas à droite.
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 36.dp).testTag("locate-me").tourTarget(TourTarget.LOCATE),
        ) {
            if (locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("◎ Me localiser")
        }
    }
}

private fun toFeatures(segments: List<List<Position>>): FeatureCollection {
    val features = segments.flatMap { seg ->
        val points = seg.map { Point.fromLngLat(it.lon, it.lat) }
        buildList {
            if (points.size > 1) add(Feature.fromGeometry(LineString.fromLngLats(points)))
            points.forEach { add(Feature.fromGeometry(it)) }
        }
    }
    return FeatureCollection.fromFeatures(features)
}

private fun trailLine(trail: List<Position>): FeatureCollection =
    if (trail.size < 2) FeatureCollection.fromFeatures(emptyList<Feature>())
    else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(trail.map { Point.fromLngLat(it.lon, it.lat) })))

private fun trailEnds(trail: List<Position>): FeatureCollection {
    if (trail.isEmpty()) return FeatureCollection.fromFeatures(emptyList<Feature>())
    fun end(p: Position, kind: String) = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("kind", kind) }
    return FeatureCollection.fromFeatures(listOf(end(trail.first(), "start"), end(trail.last(), "end")))
}

const val TRAIL_COLOR = "#1C6DD0"
private const val CORRIDOR_LAYER = "trail-corridor"

/**
 * Épaisseur en pixels d'une bande de 2 × [meters] à la latitude [lat], à tout zoom : la carte double d'échelle
 * à chaque niveau, d'où une interpolation exponentielle de base 2 entre deux niveaux extrêmes.
 */
private fun corridorWidthExpression(meters: Int, lat: Double): Expression {
    val metersPerPixelZ0 = 78_271.517 * cos(Math.toRadians(lat))
    fun widthAt(z: Int) = (2 * meters * 2.0.pow(z) / metersPerPixelZ0).toFloat()
    return Expression.interpolate(Expression.exponential(2f), Expression.zoom(), Expression.stop(0, widthAt(0)), Expression.stop(22, widthAt(22)))
}
private const val TRAIL_START_COLOR = "#2B8A3E"
private const val TRAIL_END_COLOR = "#C92A2A"
private fun noteFeatures(notes: List<SearchNote>): FeatureCollection =
    FeatureCollection.fromFeatures(
        notes.mapNotNull { note ->
            val lat = note.lat ?: return@mapNotNull null
            val lon = note.lon ?: return@mapNotNull null
            Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                addStringProperty("id", note.id)
                addStringProperty("cat", note.type.category.name)
            }
        },
    )