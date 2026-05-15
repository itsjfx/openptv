package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.LocationProvider
import ac.jfx.openptv.core.model.Coordinates
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [LocationProvider]. Uses `android.location.LocationManager` with `NETWORK_PROVIDER` (and
 * a `GPS_PROVIDER` fall-back) — deliberately not `FusedLocationProviderClient`, which is part of
 * Google Play Services. The whole project's GrapheneOS constraint means GMS is off the table; a
 * detekt rule (`ForbidPlayServices`) in `:lint:detekt` keeps that boundary enforceable in CI.
 *
 * **Coarse-or-fine.** PTV stops aren't block-precise — the nearby map screen (issue #37) doesn't
 * need anything tighter than ~100 m. Since issue #91 the screen requests COARSE + FINE together
 * so Android 12+ shows the user the Precise/Approximate toggle; this provider treats either grant
 * as sufficient (the `hasLocationPermission` check below is an OR). We don't tighten the
 * `LocationManager` provider choice or callback cadence based on the grant — coarse-resolution
 * fixes match the visible-state-change resolution of the map either way.
 *
 * **Behaviour on permission missing / providers disabled.** Both methods absorb the failure
 * locally instead of bubbling a `SecurityException` up to the caller:
 *
 *  - [lastKnown] returns `null`.
 *  - [observe] completes the flow cleanly (no `throw`) — that's an acceptance criterion for
 *    issue #36 so ViewModels don't have to handle a "permission revoked mid-collection" branch.
 *
 * **Why `awaitClose` + callbacks rather than a Channel-flow loop.** `LocationManager`'s
 * callback-based API is the natural shape for `callbackFlow`; the only state we own is the
 * listener registration, which `awaitClose { manager.removeUpdates(listener) }` cleans up
 * deterministically when the collector cancels.
 */
@Singleton
internal class LocationManagerLocationProvider
    @Inject
    constructor(
        // Use `@param:` to keep the annotation scoped to the constructor parameter only; without
        // it Kotlin 2.x warns about the future default of also applying it to the generated
        // backing field (which Hilt doesn't read). See KT-73255.
        @param:ApplicationContext private val context: Context,
    ) : LocationProvider {
        private val manager: LocationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        override suspend fun lastKnown(): Coordinates? {
            if (!hasLocationPermission()) return null
            // Try NETWORK_PROVIDER first — coarse and quick; GPS_PROVIDER is the fall-back when
            // the device is offline or hasn't seen a cell tower fix. `getLastKnownLocation` returns
            // null if the provider is disabled or has never produced a fix; we treat both the same.
            return try {
                val networkFix = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                val gpsFix = networkFix ?: manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                gpsFix?.toCoordinates()
            } catch (_: SecurityException) {
                // Permission revoked between hasLocationPermission() and the call — return null
                // rather than bubble. Same contract as the permission-missing-at-entry path above.
                null
            }
        }

        override fun observe(): Flow<Coordinates> =
            callbackFlow {
                if (!hasLocationPermission()) {
                    close()
                    return@callbackFlow
                }

                // Prefer NETWORK_PROVIDER; fall back to GPS if Network isn't enabled (rural Vic,
                // airplane mode + GPS on). If neither is enabled, complete the flow — caller can
                // observe `isProviderEnabled` if it wants a richer "no location available" state.
                val provider =
                    when {
                        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                            LocationManager.NETWORK_PROVIDER
                        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                            LocationManager.GPS_PROVIDER
                        else -> {
                            close()
                            return@callbackFlow
                        }
                    }

                val listener =
                    object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            trySend(location.toCoordinates())
                        }

                        override fun onProviderDisabled(provider: String) {
                            // Provider disabled mid-stream (user toggled Location off, or
                            // GrapheneOS revoked it). Complete cleanly rather than throw.
                            close()
                        }

                        // The other two `LocationListener` methods (`onProviderEnabled`,
                        // `onStatusChanged`) are no-ops on API 30+ and irrelevant to coarse
                        // tracking; the defaults inherited from the interface are fine.
                    }

                try {
                    manager.requestLocationUpdates(
                        provider,
                        MIN_TIME_MS,
                        MIN_DISTANCE_M,
                        listener,
                    )
                } catch (_: SecurityException) {
                    // Permission race — same handling as lastKnown(): close silently.
                    close()
                    return@callbackFlow
                }

                awaitClose { manager.removeUpdates(listener) }
            }

        // OR with ACCESS_FINE_LOCATION because the screen now requests both together (issue #91)
        // and a user who picked "Precise" only has FINE granted, while "Approximate" gets COARSE.
        private fun hasLocationPermission(): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED

        private fun Location.toCoordinates(): Coordinates = Coordinates(lat = latitude, lng = longitude)

        private companion object {
            // 10 s between callbacks. Coarse-only consumers (nearby map, favourites' Nearest sort)
            // don't need anything tighter — a faster cadence would chew battery for no UX gain.
            const val MIN_TIME_MS: Long = 10_000L

            // 50 m between callbacks. Same rationale: PTV stops have walking-distance granularity,
            // so a callback every 50 m matches the actual visible-state-change resolution.
            const val MIN_DISTANCE_M: Float = 50f
        }
    }
