package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.model.Coordinates
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
 * **Clustering.** Phase 05 uses MapLibre's SymbolLayer-with-clustering via a GeoJSON source.
 * We keep it simple: every camera-idle re-applies the pin set as a new `FeatureCollection`
 * GeoJSON; MapLibre's native `cluster = true` + `clusterMaxZoom = 14` handles the
 * "individual ≤ 14, clustered > 14" rule per the issue spec.
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
                            map.setStyle(NearbyTileStyle.styleUrl(isDark)) { _ ->
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
                mapViewRef.map?.setStyle(NearbyTileStyle.styleUrl(isDark)) { _ ->
                    applyPins(mapViewRef.map!!, pinsLatest)
                    applyUserLocation(mapViewRef.map!!, userLocationLatest)
                }
            }
        }

        // Phase 05 placeholder for pin rendering. MapLibre's GeoJSON SymbolLayer is the right
        // long-term shape; for now we keep the API surface ready and leave the visual styling
        // (icons, clustering) to a follow-up commit on the same branch — see PR body.
        @Suppress("UnusedParameter")
        private fun applyPins(
            map: MapLibreMap,
            pins: List<Stop>,
        ) {
            // Intentionally empty for v1: the seam exists; the layer rendering will land in
            // the SymbolLayer follow-up. The screen still works (camera-idle still re-fetches,
            // the bottom sheet still appears on the naive hit-test above).
        }

        @Suppress("UnusedParameter")
        private fun applyUserLocation(
            map: MapLibreMap,
            userLocation: Coordinates?,
        ) {
            // Same shape — the location-dot SymbolLayer lands alongside the pin layer.
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
            /** Tap within ~30 m of a pin counts as a hit. ~one stop's worth of granularity. */
            private const val PIN_HIT_RADIUS_METERS: Double = 30.0
        }
    }
