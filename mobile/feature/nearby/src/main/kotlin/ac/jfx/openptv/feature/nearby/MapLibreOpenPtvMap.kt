package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
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
 * **Rendering scheme.** Stops land on a single [GeoJsonSource] and render as one [CircleLayer]
 * per stop, coloured per [RouteType] via a `match` expression.
 *
 * **No clustering (issue #124).** MapLibre's native GeoJSON clustering was enabled originally, but
 * its supercluster tile index dropped every point outside a narrow zoom band: zooming far out
 * collapsed the metro into nothing visible, and zooming far in returned an empty tile so the pins
 * vanished entirely (both reproducible on the emulator). Rendering the raw [GeoJsonSource]
 * unclustered shows every cached stop at every zoom, which is what the issue actually wants ("see
 * stops way more"). The LRU stop cache ([NearbyMapSessionCache.MAX_CACHED_STOPS]) bounds how many
 * pins can be on screen at once, so the unclustered point count stays well within MapLibre's
 * comfort zone.
 *
 * **Co-located stops (issue #172).** Different modes routinely share one physical station, and PTV
 * gives them identical coordinates — e.g. the Richmond train stop and the V/Line "Richmond Railway
 * Station" are both `stop_id 1162` at the same lat/lng. Rendered as-is their circles stack on the
 * same pixel and only the top one is visible/tappable. [applyPins] fans any group of stops sharing
 * a coordinate out around a small fixed-radius circle (see [spreadColocatedStops]); a lone stop
 * keeps its exact coordinate. We deliberately do *not* re-enable clustering to fix this (that brings back
 * the #124 zoom-extreme bug) — the offset is a few metres, well under one stop's spacing.
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
        @Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
        override fun Render(
            camera: OpenPtvCameraState,
            userLocation: Coordinates?,
            userBearing: Float?,
            pins: List<Stop>,
            isDark: Boolean,
            onCameraIdle: (OpenPtvCameraState) -> Unit,
            onCameraMoveStarted: (CameraMoveReason) -> Unit,
            onPinClicked: (Stop) -> Unit,
            modifier: Modifier,
        ) {
            // `rememberUpdatedState` so the closures captured by MapLibre's listeners always see
            // the freshest callback — without this, a recomposition that swaps the lambda would
            // leave MapLibre's listener pointing at the stale one.
            val onCameraIdleLatest by rememberUpdatedState(onCameraIdle)
            val onCameraMoveStartedLatest by rememberUpdatedState(onCameraMoveStarted)
            val onPinClickedLatest by rememberUpdatedState(onPinClicked)
            val pinsLatest by rememberUpdatedState(pins)
            val userLocationLatest by rememberUpdatedState(userLocation)
            val userBearingLatest by rememberUpdatedState(userBearing)
            val lifecycleOwner = LocalLifecycleOwner.current

            // Hold a single MapView across recompositions. Compose's `remember` keyed by no inputs
            // keeps the same instance as long as the composition is alive; the DisposableEffect
            // below calls `onDestroy` on dispose to clean up the EGL context.
            val mapViewRef = remember { MapViewRef() }

            // Track when MapLibre's async style/map setup has finished. The `LaunchedEffect`s
            // below that push state into MapView are gated on this — without it, an effect that
            // fires before the map is ready (e.g. issue #123 focus consume during the permission
            // round-trip) silently no-ops because `mapViewRef.map` is null. The flag flips inside
            // the factory's `getMapAsync` callback and stays true for the rest of the
            // composition's lifetime, so subsequent state changes always reach the map.
            var mapReady by remember { mutableStateOf(false) }

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
                                installUserLocationLayers(style)
                                applyPins(map, pinsLatest)
                                applyUserLocation(map, userLocationLatest, userBearingLatest)
                            }
                            // Flip the ready flag so the camera/pins LaunchedEffects below
                            // re-fire now that `mapViewRef.map` is non-null. The initial camera
                            // is NOT set here — the `LaunchedEffect(camera, mapReady)` below is
                            // the single source of truth so the latest camera always wins,
                            // including across the async setup window when the VM may transition
                            // (issue #123 focus consume during the permission round-trip).
                            mapReady = true
                            map.addOnCameraIdleListener {
                                // Issue #146: drop idles fired before the composition has applied
                                // its first camera — they carry MapLibre's default world frame
                                // (0,0 zoom 0), not anything the user or the VM asked for. A tab
                                // away/back recreates the MapView against a retained ViewModel
                                // whose programmatic-idle guard has long been disarmed, so without
                                // this gate the phantom frame lands in VM state, refetches "stops
                                // near Null Island", and gets recorded as the session viewport.
                                if (!mapViewRef.initialCameraApplied) return@addOnCameraIdleListener
                                val pos = map.cameraPosition
                                val target = pos.target ?: return@addOnCameraIdleListener
                                onCameraIdleLatest(
                                    OpenPtvCameraState(
                                        centre = Coordinates(lat = target.latitude, lng = target.longitude),
                                        zoom = pos.zoom,
                                    ),
                                )
                            }
                            // Forward MapLibre's move-started signal so the ViewModel can cancel
                            // any in-flight pin fetch the moment the camera starts moving — issue
                            // #109. The reason int is mapped to a domain enum so the VM can gate
                            // its focus-suppression slot (PR #139 follow-up): a programmatic
                            // animateCamera triggered by our own `focusOn` ALSO fires
                            // move-started, and if we cleared the slot on every move-started the
                            // very next stale pre-animation idle would sail through the guard
                            // and clobber the focus camera.
                            map.addOnCameraMoveStartedListener { reason ->
                                val mapped =
                                    when (reason) {
                                        MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE ->
                                            CameraMoveReason.USER_GESTURE
                                        else -> CameraMoveReason.PROGRAMMATIC
                                    }
                                onCameraMoveStartedLatest(mapped)
                            }
                            // Tap detection runs through a custom touch listener instead of
                            // MapLibre's built-in `addOnMapClickListener`. The View-side click
                            // listener reliably fails to fire when MapView lives inside a
                            // `BottomSheetScaffold.content` slot (PR #84 bug #4) — Compose's
                            // pointer pipeline appears to swallow the synthesised
                            // single-tap-confirmed event even though raw drag events still reach
                            // the MapView. Watching the raw MotionEvents ourselves and treating a
                            // small DOWN→UP delta as a tap sidesteps the problem entirely. We
                            // return false from the listener so MapView's own gesture detector
                            // still receives every event (pan + pinch zoom keep working); the tap
                            // detection is purely additive.
                            val touchSlop = mapView.context.resources.displayMetrics.density * TAP_SLOP_DP
                            var downX = 0f
                            var downY = 0f
                            var downTime = 0L
                            mapView.setOnTouchListener { _, ev ->
                                when (ev.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        downX = ev.x
                                        downY = ev.y
                                        downTime = ev.eventTime
                                    }
                                    MotionEvent.ACTION_UP -> {
                                        val dx = ev.x - downX
                                        val dy = ev.y - downY
                                        val moved = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
                                        val held = ev.eventTime - downTime
                                        if (moved <= touchSlop && held <= TAP_MAX_DURATION_MS) {
                                            val latLng =
                                                map.projection.fromScreenLocation(
                                                    android.graphics.PointF(ev.x, ev.y),
                                                )
                                            val tap =
                                                Coordinates(lat = latLng.latitude, lng = latLng.longitude)
                                            // Issue #172: hit-test against the displayed positions so
                                            // co-located stops fanned apart by [spreadColocatedStops]
                                            // are each individually tappable; tapping the shared
                                            // underlying coordinate would otherwise always resolve to
                                            // the same one.
                                            val hit =
                                                spreadColocatedStops(pinsLatest).minByOrNull { (_, coord) ->
                                                    tap.distanceTo(coord)
                                                }
                                            val dist = hit?.let { tap.distanceTo(it.second) }
                                            if (hit != null && dist != null && dist <= PIN_HIT_RADIUS_METERS) {
                                                onPinClickedLatest(hit.first)
                                            }
                                        }
                                    }
                                }
                                // Return false so MapView's internal gesture detector still gets
                                // every MotionEvent for pan/zoom — our tap detection is additive.
                                false
                            }
                        }
                    }
                },
                // No `update` block — pin and camera updates are routed through the keyed
                // `LaunchedEffect`s below so they only fire when their input actually changes.
                // Leaving `update` to re-run on every recomposition was rebuilding the
                // FeatureCollection (and re-firing `setGeoJson`) at the bottom-sheet animation
                // frame rate, which made the map feel sluggish on chip toggle / pan. See PR #84
                // bug #5.
                update = { /* see LaunchedEffect(pins) / LaunchedEffect(camera) below */ },
            )

            // Push pin updates into the GeoJsonSource only when the pin set actually changes.
            // `setGeoJson` is the right way to swap features in place (MapLibre diffs and
            // re-renders only what moved); the bug was that we were calling it on every
            // recomposition, including the bottom-sheet's animation frames. Keyed on
            // `mapReady` too so the very first pin set lands as soon as the async setup
            // finishes, even if `pins` hasn't changed since the original composition.
            LaunchedEffect(pins, mapReady) {
                if (mapReady) mapViewRef.map?.let { map -> applyPins(map, pins) }
            }

            // Likewise for the camera — animate only when the requested camera changes. Also
            // keyed on `mapReady` so a camera that arrived during MapLibre's async setup
            // (issue #123 focus consume during the permission round-trip) gets applied the
            // moment the map is ready, instead of being silently dropped because
            // `mapViewRef.map` was still null. Without this, the map would render with no
            // camera at all (MapLibre defaults to centre 0,0 zoom 0 — visibly broken) until
            // the VM emitted a different camera, by which time MapLibre's own initial
            // `onCameraIdle` had usually pushed a phantom centre into the VM state already.
            LaunchedEffect(camera, mapReady) {
                if (mapReady) {
                    val update =
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(camera.centre.lat, camera.centre.lng))
                                .zoom(camera.zoom)
                                .build(),
                        )
                    val map = mapViewRef.map ?: return@LaunchedEffect
                    if (mapViewRef.initialCameraApplied) {
                        map.animateCamera(update)
                    } else {
                        // First application after (re)creation: jump instantly instead of
                        // animating from MapLibre's default world frame (issue #146). The
                        // instant move means the camera position is the target before any
                        // subsequent idle fires, so the idle gate above can safely open —
                        // and re-entering the map doesn't replay a globe-to-street zoom.
                        map.moveCamera(update)
                        mapViewRef.initialCameraApplied = true
                    }
                }
            }

            LaunchedEffect(userLocation, userBearing, mapReady) {
                if (mapReady) mapViewRef.map?.let { map -> applyUserLocation(map, userLocation, userBearing) }
            }

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
                    installUserLocationLayers(style)
                    applyPins(mapViewRef.map!!, pinsLatest)
                    applyUserLocation(mapViewRef.map!!, userLocationLatest, userBearingLatest)
                }
            }
        }

        /**
         * One-time install: a plain (unclustered) [GeoJsonSource] plus the single circle layer
         * that projects each stop to a coloured dot. Idempotent — a no-op if the source is already
         * present (style reloads on theme flip). The source starts empty; [applyPins] swaps the
         * `FeatureCollection` in on every camera-idle update.
         *
         * Clustering was removed in issue #124 — see the class KDoc for why. With no clustering
         * every feature is a stop, so there's no `point_count` filter to apply.
         */
        private fun installPinLayers(style: Style) {
            if (style.getSource(SOURCE_PINS) != null) return

            style.addSource(GeoJsonSource(SOURCE_PINS))

            // Single-stop circle, colour driven by the stop's route type. The match expression
            // maps each PTV wire code to a constant; falls back to grey for Unknown.
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
            style.addLayer(pinCircle)
        }

        /**
         * Swap in the latest pin set. Builds a [FeatureCollection] of [Point]s with `routeType`
         * + `stopId` properties so the styling expression in [installPinLayers] can colour each
         * stop by route type without us having to maintain per-type sources.
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
            // Issue #172: draw each stop at its (possibly fanned-out) display position so
            // co-located stops don't stack on one pixel. [spreadColocatedStops] is the single source
            // of these positions — the tap hit-test uses it too, so a fanned-out dot is tappable
            // where it's actually drawn rather than at the shared underlying coordinate.
            val features =
                spreadColocatedStops(pins).map { (stop, coord) ->
                    Feature.fromGeometry(Point.fromLngLat(coord.lng, coord.lat)).apply {
                        addNumberProperty(GEOJSON_PROP_ROUTE_TYPE, stop.routeType.toCode())
                        addNumberProperty(GEOJSON_PROP_STOP_ID, stop.id.value)
                    }
                }
            source.setGeoJson(FeatureCollection.fromFeatures(features))
        }

        /**
         * One-time install of the user-location layers (issue #99): a [GeoJsonSource] holding at
         * most one point, a heading-cone [SymbolLayer] underneath, an accuracy halo [CircleLayer]
         * for soft contrast, and the blue-dot [CircleLayer] on top. The cone uses an icon image
         * registered against the style so it can be rotated per-feature via `iconRotate`.
         *
         * The cone sits below the dot in z-order (drawn first) so the dot occludes the cone's
         * apex — same convention Google Maps + Apple Maps use for their blue-dot indicator.
         *
         * Idempotent — early return if the source is already installed (style reloads on theme
         * flip).
         */
        private fun installUserLocationLayers(style: Style) {
            if (style.getSource(SOURCE_USER) != null) return

            // Register the heading-cone bitmap once. Drawn programmatically (rather than as an
            // XML drawable) because the cone's geometry / colour is parameterised on constants
            // we keep in this file — exporting it to a vector asset would duplicate the source
            // of truth.
            style.addImage(IMAGE_USER_CONE, buildConeBitmap())

            style.addSource(GeoJsonSource(SOURCE_USER))

            // Heading cone — only rendered when the feature carries a `bearing` property. The
            // `has` filter lets us register the layer once but suppress drawing when the device
            // has no compass (no `bearing` property set in [applyUserLocation]).
            val coneLayer =
                SymbolLayer(LAYER_USER_CONE, SOURCE_USER).withProperties(
                    PropertyFactory.iconImage(IMAGE_USER_CONE),
                    PropertyFactory.iconRotate(
                        Expression.toNumber(Expression.get(GEOJSON_PROP_BEARING)),
                    ),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    // Anchor the cone at the bottom centre so its apex sits at the user's fix —
                    // visually the cone "comes out of" the dot like a flashlight beam.
                    PropertyFactory.iconAnchor("bottom"),
                    PropertyFactory.iconSize(USER_CONE_SCALE),
                )
            coneLayer.setFilter(Expression.has(GEOJSON_PROP_BEARING))
            style.addLayer(coneLayer)

            // Soft accuracy halo behind the dot — gives the indicator visual weight on busy
            // streetscapes without resorting to a real accuracy radius (which would require a
            // metres-to-pixels conversion based on the current camera zoom).
            val halo =
                CircleLayer(LAYER_USER_HALO, SOURCE_USER).withProperties(
                    PropertyFactory.circleColor(USER_HALO_COLOR),
                    PropertyFactory.circleRadius(USER_HALO_RADIUS_PX),
                    PropertyFactory.circleOpacity(USER_HALO_OPACITY),
                )
            style.addLayer(halo)

            // The blue dot itself. Two-tone (filled + white stroke) so the indicator is legible
            // on both light and dark map styles. Same ARGB convention as the pin layers.
            val dot =
                CircleLayer(LAYER_USER_DOT, SOURCE_USER).withProperties(
                    PropertyFactory.circleColor(USER_DOT_COLOR),
                    PropertyFactory.circleRadius(USER_DOT_RADIUS_PX),
                    PropertyFactory.circleStrokeColor(STROKE_COLOR),
                    PropertyFactory.circleStrokeWidth(USER_DOT_STROKE_WIDTH_PX),
                )
            style.addLayer(dot)
        }

        /**
         * Push the user-location feature into the source. Empty collection when [userLocation] is
         * `null` (hides the dot — issue #99: "only shows if location permission is granted").
         * When [bearing] is `null` we still emit the feature but skip the `bearing` property, so
         * the cone layer's `has(bearing)` filter suppresses it — that's the "device without a
         * compass" path (just the dot, no cone).
         *
         * No-op if the style hasn't loaded yet — the next call after the style callback fires
         * will pick the latest values up.
         */
        private fun applyUserLocation(
            map: MapLibreMap,
            userLocation: Coordinates?,
            bearing: Float?,
        ) {
            val style = map.style ?: return
            val source = style.getSourceAs<GeoJsonSource>(SOURCE_USER) ?: return
            if (userLocation == null) {
                source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
                return
            }
            val feature =
                Feature.fromGeometry(
                    Point.fromLngLat(userLocation.lng, userLocation.lat),
                ).apply {
                    if (bearing != null) addNumberProperty(GEOJSON_PROP_BEARING, bearing)
                }
            source.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
        }

        /**
         * Programmatically build the heading-cone bitmap. A filled isoceles triangle with the
         * apex at the bottom centre — combined with `iconAnchor("bottom")` in
         * [installUserLocationLayers] the apex lands exactly on the user's fix and the cone
         * spreads "up" (north when bearing is 0, then rotated per `iconRotate`).
         *
         * Drawn with a vertical alpha gradient (opaque at the apex, fading out at the wide end)
         * so the cone reads as "direction of facing" rather than a solid wedge — same look as
         * Google Maps' blue cone. Falling back to a solid fill would still work but reads as
         * more aggressive on a dense map.
         */
        @Suppress("MagicNumber")
        private fun buildConeBitmap(): Bitmap {
            val bitmap = Bitmap.createBitmap(CONE_BITMAP_PX, CONE_BITMAP_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val width = CONE_BITMAP_PX.toFloat()
            val height = CONE_BITMAP_PX.toFloat()
            val apexX = width / 2f
            val apexY = height
            val halfBase = width * CONE_HALF_BASE_FRACTION
            val baseY = height * CONE_TIP_HEIGHT_FRACTION

            val path =
                Path().apply {
                    moveTo(apexX, apexY)
                    lineTo(apexX - halfBase, baseY)
                    lineTo(apexX + halfBase, baseY)
                    close()
                }

            // Linear gradient from opaque at the apex (bottom) to transparent at the wide end
            // (top). `android.graphics.LinearGradient` would be cleaner but pulls in another
            // import — the manual alpha-stepped fill below is one paint operation per band and
            // produces an indistinguishable result at this size.
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                }
            // Draw the full cone first as a base layer with mid alpha.
            paint.color = USER_CONE_COLOR
            paint.alpha = CONE_BASE_ALPHA
            canvas.drawPath(path, paint)
            return bitmap
        }

        /**
         * Holds the live `MapView` + `MapLibreMap` references across recompositions. A plain
         * `var` inside `remember { }` works but a typed holder reads better at call sites.
         */
        private class MapViewRef {
            var view: MapView? = null
            var map: MapLibreMap? = null

            /**
             * Flips true once the composition has pushed the first camera into this MapView
             * (issue #146). A brand-new MapView sits at MapLibre's default frame (centre 0,0,
             * zoom 0) until then, and fires camera-idle events for that frame during async
             * setup — the idle listener drops everything until this flag flips so a phantom
             * "Null Island" centre never reaches the ViewModel (where it would overwrite the
             * real camera state and get recorded as the session's last viewport).
             */
            var initialCameraApplied: Boolean = false
        }

        private companion object {
            /**
             * Tap within ~80 m of a pin counts as a hit. Original Phase 05 default of 30 m was
             * tuned to the metric "one stop's worth of granularity" but in practice that's only
             * 5-10 screen pixels at a typical map zoom (14+), which is below a finger-tip
             * tap's accuracy on a typical phone. Bumping to ~80 m gives the tap surface enough
             * slop that a deliberate tap on a pin actually lands. PTV stops in central
             * Melbourne are typically 100-200 m apart, so a tap still resolves to the right
             * stop unambiguously.
             *
             * The naive distance hit-test runs in lat/lng space and is invariant to the current
             * camera zoom, so a tap that "looks close" to a pin at zoom 18 is also "close" at
             * zoom 14. The wider radius is a usability tradeoff favouring "the tap registers"
             * over "I might hit the wrong nearby stop" — the bottom sheet anyway shows the
             * stop name front-and-centre so the user spots a wrong-pin hit immediately and
             * dismisses.
             */
            private const val PIN_HIT_RADIUS_METERS: Double = 80.0

            /**
             * Tap detection threshold — DOWN→UP movement (in pixels at 1x density) below this
             * counts as a tap. Anything larger is a drag and we ignore it (MapView handles
             * panning natively). Picked to roughly match `ViewConfiguration.scaledTouchSlop` on
             * a typical phone (~8-12 dp) — generous enough that a fingertip tap doesn't get
             * mis-classified as a drag, tight enough that a deliberate swipe doesn't trigger a
             * spurious pin hit at the start of the gesture.
             */
            private const val TAP_SLOP_DP: Float = 16f

            /**
             * Tap-duration ceiling. Touches held longer than this are treated as long-press /
             * drag-init and ignored, even if the movement was small. Matches Android's default
             * long-press threshold so the tap surface lines up with what the user expects from
             * other apps.
             */
            private const val TAP_MAX_DURATION_MS: Long = 500L

            // GeoJSON source + layer ids
            private const val SOURCE_PINS = "openptv-stops"
            private const val LAYER_UNCLUSTERED = "openptv-stops-unclustered"

            // User-location source + layers (issue #99).
            private const val SOURCE_USER = "openptv-user"
            private const val LAYER_USER_CONE = "openptv-user-cone"
            private const val LAYER_USER_HALO = "openptv-user-halo"
            private const val LAYER_USER_DOT = "openptv-user-dot"
            private const val IMAGE_USER_CONE = "openptv-user-cone-icon"

            // Property keys on each Feature — referenced from style expressions.
            private const val GEOJSON_PROP_ROUTE_TYPE = "routeType"
            private const val GEOJSON_PROP_STOP_ID = "stopId"
            private const val GEOJSON_PROP_BEARING = "bearing"

            // Circle radii in pixels — tuned visually on a 411-dp device. Constants rather than
            // theme-derived because the map is tile-rendered and Compose's `dp` doesn't apply.
            private const val PIN_RADIUS_PX: Float = 7f
            private const val PIN_STROKE_WIDTH_PX: Float = 2f

            // ARGB ints. Picked to read on both light and dark map styles.
            private const val STROKE_COLOR: Int = 0xFFFFFFFF.toInt()

            // User-location indicator colours + sizes (issue #99). Material blue 500 keeps the
            // dot in the same "current location" colour family every other map app uses, and
            // reads clearly against the route-type stop colours it sits among.
            private const val USER_DOT_COLOR: Int = 0xFF1E88E5.toInt() // Material blue 500
            private const val USER_CONE_COLOR: Int = 0xFF1E88E5.toInt()
            private const val USER_HALO_COLOR: Int = 0xFF1E88E5.toInt()
            private const val USER_DOT_RADIUS_PX: Float = 8f
            private const val USER_DOT_STROKE_WIDTH_PX: Float = 3f
            private const val USER_HALO_RADIUS_PX: Float = 18f
            private const val USER_HALO_OPACITY: Float = 0.18f

            // Heading-cone bitmap geometry. The bitmap is square; the cone fills the bottom
            // half + a bit (apex centred at the bottom edge, wide end at the top). The
            // `iconAnchor("bottom")` SymbolLayer property pins the apex to the dot's centre.
            private const val CONE_BITMAP_PX: Int = 128
            private const val CONE_HALF_BASE_FRACTION: Float = 0.35f
            private const val CONE_TIP_HEIGHT_FRACTION: Float = 0.0f
            private const val CONE_BASE_ALPHA: Int = 110
            private const val USER_CONE_SCALE: Float = 0.6f

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
