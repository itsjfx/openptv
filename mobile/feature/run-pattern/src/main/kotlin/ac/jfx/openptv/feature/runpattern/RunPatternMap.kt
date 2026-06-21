package ac.jfx.openptv.feature.runpattern

import ac.jfx.openptv.core.model.Bounds
import ac.jfx.openptv.core.model.RouteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The collapsible run-pattern map (issue #187). Draws the route's geopath as a [LineLayer], every
 * stop the run calls at as a [CircleLayer] dot, and the "you are here" origin stop as a highlighted
 * dot, then frames the camera to fit all of it.
 *
 * **Why a dedicated map here instead of reusing `:feature:nearby`'s [`OpenPtvMap`].** Nearby's map
 * seam is welded to its pan/fetch/follow-me lifecycle (camera-idle drives PTV refetches, a custom
 * tap hit-test, a user-location cone). The run-pattern map is read-only and static — a line plus
 * fixed markers framed once — so wrapping that interface would mean threading half a dozen
 * unrelated callbacks through. This composable keeps the same MapLibre-behind-`AndroidView` +
 * `LifecycleEventObserver` patterns nearby established, but stays self-contained.
 *
 * MapLibre is initialised process-wide in `OpenPtvApplication.onCreate` (via the nearby module's
 * initialiser), so by the time this `MapView` is constructed the SDK singleton already exists.
 *
 * Graceful degradation: the caller (`RunPatternScreen`) only mounts this when
 * [RunPatternMapData.hasGeometry] is true, so this composable always has something to draw.
 */
@Composable
@Suppress("LongMethod")
internal fun RunPatternMap(
    mapData: RunPatternMapData,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val mapDataLatest by rememberUpdatedState(mapData)
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapViewRef = remember { RunPatternMapViewRef() }
    var mapReady by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).also { mapView ->
                mapViewRef.view = mapView
                mapView.onCreate(null)
                mapView.getMapAsync { map ->
                    mapViewRef.map = map
                    // The map is read-only context, not an interactive surface — disable the
                    // gestures that would let the user pan/rotate away from the framed route so the
                    // map keeps showing the line it's there to illustrate.
                    map.uiSettings.apply {
                        setAllGesturesEnabled(false)
                        isZoomGesturesEnabled = true
                        isLogoEnabled = false
                        isAttributionEnabled = true
                    }
                    map.setStyle(RunPatternTileStyle.styleUrl(isDark)) { style ->
                        installLayers(style)
                        applyGeometry(map, mapDataLatest)
                        frameCamera(map, mapDataLatest.bounds, animate = false)
                    }
                    mapReady = true
                }
            }
        },
        update = { /* geometry + camera are routed through the keyed LaunchedEffects below */ },
    )

    // Re-apply geometry whenever the data changes (a poll can flip a stop from upcoming to
    // departed, which recolours its marker). Keyed on `mapReady` so the first apply lands as soon
    // as the async style load finishes.
    LaunchedEffect(mapData, mapReady) {
        if (mapReady) {
            mapViewRef.map?.let { map ->
                applyGeometry(map, mapData)
                if (!mapViewRef.framed) {
                    frameCamera(map, mapData.bounds, animate = false)
                }
            }
        }
    }

    // Reload the style on theme flip, then re-install layers + geometry (a style reload drops
    // everything we added). Keyed on `isDark` so it only fires on a real change.
    LaunchedEffect(isDark) {
        if (mapReady) {
            mapViewRef.map?.setStyle(RunPatternTileStyle.styleUrl(isDark)) { style ->
                installLayers(style)
                applyGeometry(mapViewRef.map!!, mapDataLatest)
                frameCamera(mapViewRef.map!!, mapDataLatest.bounds, animate = false)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                val mv = mapViewRef.view ?: return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_START -> mv.onStart()
                    Lifecycle.Event.ON_RESUME -> mv.onResume()
                    Lifecycle.Event.ON_PAUSE -> mv.onPause()
                    Lifecycle.Event.ON_STOP -> mv.onStop()
                    Lifecycle.Event.ON_DESTROY -> mv.onDestroy()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef.view?.onDestroy()
            mapViewRef.view = null
            mapViewRef.map = null
        }
    }
}

/**
 * Install the route line + stop layers once (idempotent — a no-op if already present, since a style
 * reload on theme flip re-enters here). Z-order: line at the bottom, ordinary stops, then the
 * origin highlight on top so "you are here" is never occluded.
 */
private fun installLayers(style: Style) {
    if (style.getSource(SOURCE_LINE) == null) {
        style.addSource(org.maplibre.android.style.sources.GeoJsonSource(SOURCE_LINE))
        style.addLayer(
            LineLayer(LAYER_LINE, SOURCE_LINE).withProperties(
                PropertyFactory.lineColor(
                    Expression.color(routeTypeColor(RouteType.Unknown)),
                ),
                PropertyFactory.lineWidth(LINE_WIDTH_PX),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
                PropertyFactory.lineOpacity(LINE_OPACITY),
            ),
        )
    }
    if (style.getSource(SOURCE_STOPS) == null) {
        style.addSource(org.maplibre.android.style.sources.GeoJsonSource(SOURCE_STOPS))
        style.addLayer(
            CircleLayer(LAYER_STOPS, SOURCE_STOPS).withProperties(
                PropertyFactory.circleRadius(STOP_RADIUS_PX),
                PropertyFactory.circleStrokeColor(STROKE_COLOR),
                PropertyFactory.circleStrokeWidth(STOP_STROKE_WIDTH_PX),
                // Dim departed stops via opacity so the line still reads through them.
                PropertyFactory.circleColor(
                    Expression.color(routeTypeColor(RouteType.Unknown)),
                ),
                PropertyFactory.circleOpacity(
                    Expression.match(
                        Expression.toNumber(Expression.get(PROP_DEPARTED)),
                        Expression.literal(STOP_OPACITY),
                        Expression.stop(1, Expression.literal(STOP_DEPARTED_OPACITY)),
                    ),
                ),
            ),
        )
    }
    if (style.getSource(SOURCE_ORIGIN) == null) {
        style.addSource(org.maplibre.android.style.sources.GeoJsonSource(SOURCE_ORIGIN))
        style.addLayer(
            CircleLayer(LAYER_ORIGIN, SOURCE_ORIGIN).withProperties(
                PropertyFactory.circleRadius(ORIGIN_RADIUS_PX),
                PropertyFactory.circleColor(ORIGIN_COLOR),
                PropertyFactory.circleStrokeColor(STROKE_COLOR),
                PropertyFactory.circleStrokeWidth(ORIGIN_STROKE_WIDTH_PX),
            ),
        )
    }
}

/** Swap in the latest line + stop features. No-op until the style (hence the sources) exists. */
private fun applyGeometry(
    map: MapLibreMap,
    data: RunPatternMapData,
) {
    val style = map.style ?: return
    val color = routeTypeColor(data.routeType)

    style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(SOURCE_LINE)?.let { src ->
        val lines =
            data.polyline
                .filter { it.size >= 2 }
                .map { segment ->
                    Feature.fromGeometry(
                        LineString.fromLngLats(segment.map { Point.fromLngLat(it.lng, it.lat) }),
                    )
                }
        src.setGeoJson(FeatureCollection.fromFeatures(lines))
    }
    style.getLayer(LAYER_LINE)?.setProperties(PropertyFactory.lineColor(color))

    style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(SOURCE_STOPS)?.let { src ->
        val features =
            data.markers.map { marker ->
                Feature.fromGeometry(
                    Point.fromLngLat(marker.coordinates.lng, marker.coordinates.lat),
                ).apply {
                    addNumberProperty(PROP_DEPARTED, if (marker.hasDeparted) 1 else 0)
                }
            }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }
    style.getLayer(LAYER_STOPS)?.setProperties(PropertyFactory.circleColor(color))

    style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(SOURCE_ORIGIN)?.let { src ->
        val origin =
            data.markers.firstOrNull { it.isOrigin }?.let { marker ->
                Feature.fromGeometry(Point.fromLngLat(marker.coordinates.lng, marker.coordinates.lat))
            }
        src.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(origin).toTypedArray()),
        )
    }
}

/**
 * Frame the camera to enclose [bounds] with padding. A single-point bounds (one stop, no line)
 * zooms to a sensible street-level default instead of MapLibre's degenerate "fit a zero-area box"
 * (which lands at max zoom). Sets [RunPatternMapViewRef.framed] so a later geometry update doesn't
 * re-frame and yank the view.
 */
private fun frameCamera(
    map: MapLibreMap,
    bounds: Bounds?,
    animate: Boolean,
) {
    bounds ?: return
    val sw = LatLng(bounds.southWest.lat, bounds.southWest.lng)
    val ne = LatLng(bounds.northEast.lat, bounds.northEast.lng)
    val update =
        if (sw == ne) {
            CameraUpdateFactory.newLatLngZoom(sw, SINGLE_POINT_ZOOM)
        } else {
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.Builder().include(sw).include(ne).build(),
                CAMERA_PADDING_PX,
            )
        }
    if (animate) map.animateCamera(update) else map.moveCamera(update)
}

/** Holds the live MapView/map across recompositions, plus the one-shot camera-framed flag. */
private class RunPatternMapViewRef {
    var view: MapView? = null
    var map: MapLibreMap? = null
    var framed: Boolean = false
}

/**
 * Per-mode line/stop colour — same palette as the nearby pins so a train line reads navy on both
 * surfaces. Constants rather than theme-derived because the map is GL-rendered and Compose's `dp`
 * / colour scheme doesn't flow into the tile surface.
 */
@Suppress("MagicNumber")
private fun routeTypeColor(routeType: RouteType): Int =
    when (routeType) {
        RouteType.Train -> 0xFF0F3D8C.toInt() // navy
        RouteType.Tram -> 0xFF388E3C.toInt() // green
        RouteType.Bus -> 0xFFEF6C00.toInt() // orange
        RouteType.VLine -> 0xFF6A1B9A.toInt() // purple
        RouteType.NightBus -> 0xFF3949AB.toInt() // indigo
        RouteType.Unknown -> 0xFF757575.toInt() // grey
    }

private const val SOURCE_LINE = "run-pattern-line"
private const val SOURCE_STOPS = "run-pattern-stops"
private const val SOURCE_ORIGIN = "run-pattern-origin"
private const val LAYER_LINE = "run-pattern-line-layer"
private const val LAYER_STOPS = "run-pattern-stops-layer"
private const val LAYER_ORIGIN = "run-pattern-origin-layer"

private const val PROP_DEPARTED = "departed"

private const val LINE_WIDTH_PX: Float = 5f
private const val LINE_OPACITY: Float = 0.9f
private const val STOP_RADIUS_PX: Float = 5f
private const val STOP_STROKE_WIDTH_PX: Float = 1.5f
private const val STOP_OPACITY: Float = 1f
private const val STOP_DEPARTED_OPACITY: Float = 0.35f
private const val ORIGIN_RADIUS_PX: Float = 8f
private const val ORIGIN_STROKE_WIDTH_PX: Float = 3f
private const val CAMERA_PADDING_PX: Int = 80
private const val SINGLE_POINT_ZOOM: Double = 14.0

private const val STROKE_COLOR: Int = 0xFFFFFFFF.toInt()

/** Material blue 500 — the same "you are here" colour family the nearby user-dot uses. */
private const val ORIGIN_COLOR: Int = 0xFF1E88E5.toInt()
