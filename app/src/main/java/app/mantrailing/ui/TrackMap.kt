package app.mantrailing.ui

import androidx.compose.foundation.layout.Box
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

private val STYLE_JSON = """
{
  "version": 8,
  "center": [2.4, 46.6], "zoom": 5,
  "sources": {
    "plan": { "type": "raster", "tileSize": 256, "maxzoom": 19, "attribution": "© IGN",
              "tiles": ["${ign("GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2", "image/png")}"] },
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

@Composable
fun TrackMap(segments: List<List<Position>>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var baseIndex by rememberSaveable { mutableIntStateOf(0) }

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
            m.setStyle(Style.Builder().fromJson(STYLE_JSON)) { style ->
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

    LaunchedEffect(map, segments) {
        val (m, style) = map ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(DOG_SOURCE)?.setGeoJson(toFeatures(segments))
        val points = segments.flatten().map { LatLng(it.lat, it.lon) }
        when {
            points.size == 1 -> m.easeCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 16.0))
            points.size > 1 -> m.easeCamera(
                CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 80),
            )
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView })
        FilledTonalButton(
            onClick = { baseIndex = (baseIndex + 1) % BASE_LAYERS.size },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) { Text(BASE_LAYERS[baseIndex].second) }
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
