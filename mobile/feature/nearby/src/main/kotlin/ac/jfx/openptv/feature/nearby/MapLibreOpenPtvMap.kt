package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [OpenPtvMap] impl. Wraps MapLibre Android's `MapView` inside an `AndroidView` so the
 * rest of `:feature:nearby` (and the Hilt-Compose UI tests) never touches a MapLibre type.
 *
 * **Why this is the only file in the module that imports `org.maplibre.android.*`.** The whole
 * point of the [OpenPtvMap] seam is to keep MapLibre's API behind a domain-typed boundary —
 * ViewModel / Screen code only sees [Coordinates], [Stop], [OpenPtvCameraState]. If you find
 * yourself adding a second `org.maplibre.android.*` import elsewhere in this module, that's a
 * code smell — promote whatever you're doing into a new method on this impl instead.
 *
 * **Lifecycle.** MapView's GL surface is heavyweight; we forward Compose's `LifecycleOwner` into
 * `MapView.onStart/onResume/onPause/onStop/onDestroy` via a `LifecycleEventObserver` so the
 * screen pauses tile rendering when backgrounded. Without this MapView leaks the EGL context on
 * config change.
 *
 * **Rendering scheme.** Stops land on a single [GeoJsonSource] with native clustering enabled.
 * Three layers sit on top:
 *  1. [CircleLayer] for unclustered stops, coloured per [RouteType] via a `match` expression.
 *  2. [CircleLayer] for cluster halos (sized by point count).
 *  3. [SymbolLayer] for the cluster count text label.
 *
 * Going with [CircleLayer] (rather than icon bitmaps) keeps the impl asset-free — every colour
 * is derivable from a constant, and adding a route type is one row in [routeTypeColor]. If a
 * future Roborazzi screenshot ever needs distinct icons we'll swap in a bitmap factory; until
 * then circles are the cheapest legible thing we can render.
 *
 * **Singleton.** The instance carries no state — Render is called once per screen composition
 * and each invocation creates its own `MapView`. Annotated `@Singleton` so Hilt doesn't churn
 * an instance per inject site.
 */
@Singleton
internal class MapLibreOpenPtvMap
    @Inject
    constructor() : OpenPtvMap {
        @Composable
        @Suppress("LongMethod", "LongParameterList")
        override fun Render(
            camera: OpenPtvCameraState,
            userLocation: Coordinates?,
            pins: List<Stop>,
            isDark: Boolean,
            onCameraIdle: (OpenPtvCameraState) -> Unit,
            onPinClicked: (Stop) -> Unit,
            modifier: Modifier,
        ) {
            // `rememberUpdatedState` so the closures captured by MapLibre's listeners always see
            // the freshest callback — without this, a recomposition that swaps the lambda would
            // leave MapLibre's listener pointing at the stale one.
            val onCameraIdleLatest by rememberUpdatedState(onCameraIdle)
            val onPinClickedLatest by rememberUpdatedState(onPinClicked)
            val pinsLatest by rememberUpdatedState(pins)
            val userLocationLatest by rememberUpdatedState(userLocation)

            val lifecycleOwner = LocalLifecycleOwner.current

            // Hold a single MapView across recompositions. Compose's `remember` keyed by no inputs
            // keeps the same instance as long as the composition is alive; the DisposableEffect
            // below calls `onDestroy` on dispose to clean up the EGL context.
            val mapViewRef = remember { MapViewRef() }

            AndroidView(
                modifier = modifier,
                factory = { context ->
                    MapView(context).also { mapView ->
                        mapViewRef.view = mapView
                        // MapLibre's lifecycle requires explicit onCreate before anything else.
                        // Passing null because we have no saved state — Compose owns the surface.
                        mapView.onCreate(null)
                        mapView.getMapAsync { map ->
                            mapViewRef.map = map
                            map.setStyle(NearbyTileStyle.styleUrl(isDark)) { style ->
                                installPinLayers(style)
                                applyPins(map, pinsLatest)
                                applyUserLocation(map, userLocationLatest)
                            }
                            map.cameraPosition =
                                CameraPosition.Builder()
                                    .target(LatLng(camera.centre.lat, camera.centre.lng))
                                    .zoom(camera.zoom)
                                    .build()
                            map.addOnCameraIdleListener {
                                val pos = map.cameraPosition
                                val target = pos.target ?: return@addOnCameraIdleListener
                                onCameraIdleLatest(
                                    OpenPtvCameraState(
                                        centre = Coordinates(lat = target.latitude, lng = target.longitude),
                                        zoom = pos.zoom,
                                    ),
                                )
                            }
                            map.addOnMapClickListener { latLng ->
                                // Convert the click to a screen-space hit-test over the pins.
                                // Phase 05 keeps this naive — proper SymbolLayer feature querying
                                // is a follow-up. The naive impl: find the closest pin within
                                // PIN_HIT_RADIUS_METERS of the tap.
                                val tap = Coordinates(lat = latLng.latitude, lng = latLng.longitude)
                                val hit =
                                    pinsLatest.minByOrNull { stop ->
                                        tap.distanceTo(Coordinates(stop.latitude, stop.longitude))
                                    }
                                val dist =
                                    hit?.let {
                                        tap.distanceTo(Coordinates(it.latitude, it.longitude))
                                    }
                                if (hit != null && dist != null && dist <= PIN_HIT_RADIUS_METERS) {
                                    onPinClickedLatest(hit)
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                    }
                },
                update = { mapView ->
                    // Camera updates from the ViewModel — the follow-me FAB sets a new
                    // `OpenPtvCameraState` and we animate to it. MapLibre dedupes if the camera
                    // is already there, so this is safe to fire every recomposition.
                    mapView.getMapAsync { map ->
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(camera.centre.lat, camera.centre.lng))
                                    .zoom(camera.zoom)
                                    .build(),
                            ),
                        )
                        applyPins(map, pinsLatest)
                        applyUserLocation(map, userLocationLatest)
                    }
                },
            )

            // Forward Compose's lifecycle into MapView's mirror lifecycle. Without this the GL
            // surface leaks on screen rotation / config change.
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

            // Re-apply style when theme flips. Keyed on `isDark` so it only fires on a real
            // change — MapLibre reloads the entire style + layers, which is expensive.
            LaunchedEffect(isDark) {
                mapViewRef.map?.setStyle(NearbyTileStyle.styleUrl(isDark)) { style ->
                    installPinLayers(style)
                    applyPins(mapViewRef.map!!, pinsLatest)
                    applyUserLocation(mapViewRef.map!!, userLocationLatest)
                }
            }
        }

        /**
         * One-time install: a clustering-enabled [GeoJsonSource] plus the three layers that
         * project it to circles + cluster labels. Idempotent — a no-op if the source is already
         * present (style reloads on theme flip). The source starts empty; [applyPins] swaps the
         * `FeatureCollection` in on every camera-idle update.
         */
        private fun installPinLayers(style: Style) {
            if (style.getSource(SOURCE_PINS) != null) return

            val options =
                GeoJsonOptions()
                    .withCluster(true)
                    .withClusterMaxZoom(CLUSTER_MAX_ZOOM)
                    .withClusterRadius(CLUSTER_RADIUS_PX)
            style.addSource(GeoJsonSource(SOURCE_PINS, options))

            // Cluster halo — a soft circle behind the count label. Sized in two steps so a busy
            // CBD area still looks distinguishable from a quiet suburb pair. Wrapping the
            // colour in `Expression.color` matches the same fix the unclustered layer uses —
            // a raw int works with `PropertyFactory.circleColor(int)` directly but NOT inside
            // a parent expression.
            val clusterCircle =
                CircleLayer(LAYER_CLUSTERS, SOURCE_PINS).withProperties(
                    PropertyFactory.circleColor(CLUSTER_COLOR),
                    PropertyFactory.circleRadius(
                        Expression.step(
                            Expression.get(GEOJSON_PROP_POINT_COUNT),
                            Expression.literal(CLUSTER_RADIUS_SMALL_PX),
                            Expression.literal(CLUSTER_STEP_MID),
                            Expression.literal(CLUSTER_RADIUS_MID_PX),
                            Expression.literal(CLUSTER_STEP_LARGE),
                            Expression.literal(CLUSTER_RADIUS_LARGE_PX),
                        ),
                    ),
                    PropertyFactory.circleStrokeColor(STROKE_COLOR),
                    PropertyFactory.circleStrokeWidth(CLUSTER_STROKE_WIDTH_PX),
                )
            clusterCircle.setFilter(Expression.has(GEOJSON_PROP_POINT_COUNT))
            style.addLayer(clusterCircle)

            // Cluster count label sitting on top of the halo.
            val clusterCount =
                SymbolLayer(LAYER_CLUSTER_COUNT, SOURCE_PINS).withProperties(
                    PropertyFactory.textField(Expression.toString(Expression.get(GEOJSON_PROP_POINT_COUNT))),
                    PropertyFactory.textSize(CLUSTER_LABEL_SIZE_SP),
                    PropertyFactory.textColor(LABEL_COLOR),
                    PropertyFactory.textIgnorePlacement(true),
                    PropertyFactory.textAllowOverlap(true),
                )
            clusterCount.setFilter(Expression.has(GEOJSON_PROP_POINT_COUNT))
            style.addLayer(clusterCount)

            // Unclustered single-stop circle, colour driven by the stop's route type. The match
            // expression maps each PTV wire code to a constant; falls back to grey for Unknown.
            val pinCircle =
                CircleLayer(LAYER_UNCLUSTERED, SOURCE_PINS).withProperties(
                    PropertyFactory.circleRadius(PIN_RADIUS_PX),
                    PropertyFactory.circleStrokeColor(STROKE_COLOR),
                    PropertyFactory.circleStrokeWidth(PIN_STROKE_WIDTH_PX),
                    PropertyFactory.circleColor(
                        // `match` on the integer route_type code → a colour expression.
                        // `Expression.color(int)` is required around each ARGB int — passing the
                        // raw int produces a "circle-color: Expected color but found number"
                        // error from the GL renderer at style-load time.
                        Expression.match(
                            Expression.toNumber(Expression.get(GEOJSON_PROP_ROUTE_TYPE)),
                            Expression.color(routeTypeColor(RouteType.Unknown)),
                            Expression.stop(
                                RouteType.Train.toCode(),
                                Expression.color(routeTypeColor(RouteType.Train)),
                            ),
                            Expression.stop(
                                RouteType.Tram.toCode(),
                                Expression.color(routeTypeColor(RouteType.Tram)),
                            ),
                            Expression.stop(
                                RouteType.Bus.toCode(),
                                Expression.color(routeTypeColor(RouteType.Bus)),
                            ),
                            Expression.stop(
                                RouteType.VLine.toCode(),
                                Expression.color(routeTypeColor(RouteType.VLine)),
                            ),
                            Expression.stop(
                                RouteType.NightBus.toCode(),
                                Expression.color(routeTypeColor(RouteType.NightBus)),
                            ),
                        ),
                    ),
                )
            pinCircle.setFilter(Expression.not(Expression.has(GEOJSON_PROP_POINT_COUNT)))
            style.addLayer(pinCircle)
        }

        /**
         * Swap in the latest pin set. Builds a [FeatureCollection] of [Point]s with `routeType`
         * + `stopId` properties so the styling expressions in [installPinLayers] can colour and
         * cluster without us having to maintain per-type sources.
         *
         * No-op if the style hasn't loaded yet (the source isn't installed until the style's
         * `setStyle` callback fires); the next `applyPins` call after style-load will catch up.
         */
        private fun applyPins(
            map: MapLibreMap,
            pins: List<Stop>,
        ) {
            val style = map.style ?: return
            val source = style.getSourceAs<GeoJsonSource>(SOURCE_PINS) ?: return
            val features =
                pins.map { stop ->
                    Feature.fromGeometry(Point.fromLngLat(stop.longitude, stop.latitude)).apply {
                        addNumberProperty(GEOJSON_PROP_ROUTE_TYPE, stop.routeType.toCode())
                        addNumberProperty(GEOJSON_PROP_STOP_ID, stop.id.value)
                    }
                }
            source.setGeoJson(FeatureCollection.fromFeatures(features))
        }

        @Suppress("UnusedParameter")
        private fun applyUserLocation(
            map: MapLibreMap,
            userLocation: Coordinates?,
        ) {
            // Same shape — the location-dot SymbolLayer lands alongside the pin layer. Held out
            // of #79 because the location-dot is a separate concern (Phase 05 follow-up).
        }

        /**
         * Holds the live `MapView` + `MapLibreMap` references across recompositions. A plain
         * `var` inside `remember { }` works but a typed holder reads better at call sites.
         */
        private class MapViewRef {
            var view: MapView? = null
            var map: MapLibreMap? = null
        }

        private companion object {
            /**
             * Tap within ~80 m of a pin counts as a hit. Original Phase 05 default of 30 m was
             * tuned to the metric "one stop's worth of granularity" but in practice that's only
             * 5-10 screen pixels at the unclustered zoom (14+), which is below a finger-tip
             * tap's accuracy on a typical phone. Bumping to ~80 m gives the tap surface enough
             * slop that a deliberate tap on a pin actually lands. PTV stops in central
             * Melbourne are typically 100-200 m apart, so a tap still resolves to the right
             * stop unambiguously.
             */
            private const val PIN_HIT_RADIUS_METERS: Double = 80.0

            // GeoJSON source + layer ids
            private const val SOURCE_PINS = "openptv-stops"
            private const val LAYER_UNCLUSTERED = "openptv-stops-unclustered"
            private const val LAYER_CLUSTERS = "openptv-stops-clusters"
            private const val LAYER_CLUSTER_COUNT = "openptv-stops-cluster-count"

            // Property keys on each Feature — referenced from style expressions.
            private const val GEOJSON_PROP_ROUTE_TYPE = "routeType"
            private const val GEOJSON_PROP_STOP_ID = "stopId"

            // MapLibre-supplied property on cluster features — fixed name in the SDK contract.
            private const val GEOJSON_PROP_POINT_COUNT = "point_count"

            // Clustering tunables
            private const val CLUSTER_MAX_ZOOM: Int = 14
            private const val CLUSTER_RADIUS_PX: Int = 50

            // Circle radii in pixels — tuned visually on a 411-dp device. Constants rather than
            // theme-derived because the map is tile-rendered and Compose's `dp` doesn't apply.
            private const val PIN_RADIUS_PX: Float = 7f
            private const val PIN_STROKE_WIDTH_PX: Float = 2f
            private const val CLUSTER_STROKE_WIDTH_PX: Float = 2f
            private const val CLUSTER_RADIUS_SMALL_PX: Float = 16f
            private const val CLUSTER_RADIUS_MID_PX: Float = 22f
            private const val CLUSTER_RADIUS_LARGE_PX: Float = 28f
            private const val CLUSTER_STEP_MID: Int = 10
            private const val CLUSTER_STEP_LARGE: Int = 50
            private const val CLUSTER_LABEL_SIZE_SP: Float = 12f

            // ARGB ints. Picked to read on both light and dark map styles.
            private const val STROKE_COLOR: Int = 0xFFFFFFFF.toInt()
            private const val LABEL_COLOR: Int = 0xFFFFFFFF.toInt()
            private const val CLUSTER_COLOR: Int = 0xFF1976D2.toInt() // Material blue 700

            /**
             * Per-mode pin colour. Picked to be roughly aligned with the in-app glyphs and the
             * tones PTV uses on its own signage — train navy, tram green, bus orange, V/Line
             * purple, night-bus indigo. These are constants rather than theme-derived because
             * the map is tile-rendered: pulling colours from `MaterialTheme.colorScheme` would
             * couple the map's GL surface to the Compose composition tree, which fights how
             * MapView handles surface refreshes.
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
        }
    }
