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
 * Default [LocationProvider]. Uses `android.location.LocationManager` with `GPS_PROVIDER` AND
 * `NETWORK_PROVIDER` registered together — deliberately not `FusedLocationProviderClient`, which
 * is part of Google Play Services. The whole project's GrapheneOS constraint means GMS is off the
 * table; a detekt rule (`ForbidPlayServices`) in `:lint:detekt` keeps that boundary enforceable
 * in CI.
 *
 * **Both providers registered (issue #127).** The earlier impl preferred NETWORK with a GPS
 * fall-back and picked ONE. That meant the system "location in use" indicator dropped on/off as
 * NETWORK fixes trickled in every 10 s (NETWORK can be that slow even with a tower fix when wifi
 * is the only positioning source), and the user's blue dot lagged badly on the nearby map. Now
 * we register both providers with the same callback so the user gets whichever fires first — GPS
 * for precision when outdoors, NETWORK for the quick warm-up while GPS is still acquiring. Both
 * registrations also keep Android's foreground-location indicator solidly lit while the user is on
 * the map (the indicator turns off when no provider is actively delivering updates).
 *
 * **Coarse-or-fine.** Since issue #91 the screen requests COARSE + FINE together so Android 12+
 * shows the user the Precise/Approximate toggle; this provider treats either grant as sufficient
 * (the `hasLocationPermission` check below is an OR). When only COARSE is granted, the OS
 * automatically downgrades GPS fixes to ~2 km resolution — registering GPS regardless is still
 * correct, the OS just clamps the precision.
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

                // Register against every enabled provider so the user gets whichever fires first
                // — GPS for precision once acquired, NETWORK for the quick wake-up beforehand.
                // Registering both also keeps Android's foreground-location indicator solidly lit
                // (issue #127): with only one provider registered the indicator can blink off
                // between fixes, making it look like the app has lost the user. If neither
                // provider is enabled, complete the flow — caller can observe `isProviderEnabled`
                // if it wants a richer "no location available" state.
                val providers =
                    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                        .filter { manager.isProviderEnabled(it) }
                if (providers.isEmpty()) {
                    close()
                    return@callbackFlow
                }

                // A single listener fans both providers' callbacks into the same flow. If the user
                // disables one provider mid-stream, we keep the other one alive instead of
                // completing the whole flow — only complete when the LAST provider drops.
                val activeProviders = providers.toMutableSet()
                val listener =
                    object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            trySend(location.toCoordinates())
                        }

                        override fun onProviderDisabled(provider: String) {
                            activeProviders.remove(provider)
                            if (activeProviders.isEmpty()) {
                                // Last provider gone (user toggled Location off, or GrapheneOS
                                // revoked it). Complete cleanly rather than throw.
                                close()
                            }
                        }

                        // The other two `LocationListener` methods (`onProviderEnabled`,
                        // `onStatusChanged`) are no-ops on API 30+ and irrelevant here; the
                        // defaults inherited from the interface are fine.
                    }

                try {
                    providers.forEach { provider ->
                        manager.requestLocationUpdates(
                            provider,
                            MIN_TIME_MS,
                            MIN_DISTANCE_M,
                            listener,
                        )
                    }
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
            // 2 s between callbacks. The nearby map's follow-me dot needs to track walking pace
            // (~1.4 m/s) smoothly — at the old 10 s cadence the dot teleported a full 14 m every
            // refresh, which the user perceives as "the app lost me" and which also let Android's
            // foreground-location indicator flicker off between fixes (issue #127). 2 s is the
            // upper bound on what feels live; lower won't help because GPS itself only fixes at
            // ~1 Hz on most consumer chips. The `LocationManager` contract treats this as a
            // minimum interval — the OS still throttles when the device is dozing, so the battery
            // floor is unchanged for idle states; the wake cost only applies while the user is
            // actively viewing the map.
            const val MIN_TIME_MS: Long = 2_000L

            // 5 m between callbacks. The blue dot needs to move smoothly as the user walks; the
            // old 50 m bound meant standing still vs walking half a block looked identical.
            // 5 m is roughly the GPS noise floor outdoors so we won't emit pure jitter, and it
            // matches what the user expects from a "live" map (Maps / OsmAnd are in the same
            // range). Distance is OR'd with time — a stationary user still gets a 2 s callback to
            // confirm the fix is fresh, but a moving user gets sub-2 s callbacks at finer granularity.
            const val MIN_DISTANCE_M: Float = 5f
        }
    }
