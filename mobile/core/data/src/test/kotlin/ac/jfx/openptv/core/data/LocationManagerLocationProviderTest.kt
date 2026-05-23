package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.model.Coordinates
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocationManager

/**
 * Unit tests for [LocationManagerLocationProvider] against a real (Robolectric-shadowed)
 * [LocationManager]. Per `CLAUDE.md` testing priority order, the platform API is the seam under
 * test — we don't mock `LocationManager`; Robolectric's `ShadowLocationManager` already gives
 * us setter-shaped control over last-known fixes, provider enabled state, and synthetic
 * location updates.
 *
 * Pinned via `@Config(sdk = [34])` because Robolectric 4.14.x ships SDK 34 jars; newer
 * compileSdk values don't have a matching Robolectric image yet. Same pin as
 * `FavouritesRepositoryImplTest`.
 *
 * **Permission seam.** Robolectric defaults the calling package to "no permissions"; the
 * `grant{Coarse}Permission` helper below populates the shadow app's permission set so
 * `ContextCompat.checkSelfPermission` returns `PERMISSION_GRANTED`. The "permission missing"
 * tests deliberately skip that step.
 *
 * `@Suppress("DEPRECATION")`: `setLastKnownLocation` / `getLocationUpdateListeners` are marked
 * deprecated on the public `LocationManager` API for production callers but remain the canonical
 * Robolectric hooks for seeding state and inspecting registered listeners. Robolectric's own
 * docs recommend them in tests; the deprecation targets device-side callers, not us.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [SDK_PIN])
class LocationManagerLocationProviderTest {
    private lateinit var context: Context
    private lateinit var manager: LocationManager
    private lateinit var shadowManager: ShadowLocationManager
    private lateinit var provider: LocationManagerLocationProvider

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext<Application>()
        manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowManager = shadowOf(manager)
        // Both providers enabled by default so the happy path doesn't need to remember to enable
        // them. Individual tests can disable as needed.
        shadowManager.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        provider = LocationManagerLocationProvider(context)
    }

    @Test
    fun `lastKnown returns null when no fix and permission granted`() =
        runTest {
            grantCoarsePermission()
            assertThat(provider.lastKnown()).isNull()
        }

    @Test
    fun `lastKnown returns network fix when present`() =
        runTest {
            grantCoarsePermission()
            shadowManager.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fixAt(FLINDERS_LAT, FLINDERS_LNG))

            val result = provider.lastKnown()

            assertThat(result).isEqualTo(Coordinates(FLINDERS_LAT, FLINDERS_LNG))
        }

    @Test
    fun `lastKnown falls back to GPS fix when network is null`() =
        runTest {
            grantCoarsePermission()
            shadowManager.setLastKnownLocation(LocationManager.GPS_PROVIDER, fixAt(FED_LAT, FED_LNG))

            val result = provider.lastKnown()

            assertThat(result).isEqualTo(Coordinates(FED_LAT, FED_LNG))
        }

    @Test
    fun `lastKnown returns null when permission missing`() =
        runTest {
            // Deliberately do NOT call grantCoarsePermission().
            shadowManager.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fixAt(FLINDERS_LAT, FLINDERS_LNG))

            assertThat(provider.lastKnown()).isNull()
        }

    @Test
    fun `observe registers against both GPS and NETWORK providers`() =
        runTest {
            // Issue #127 — registering both providers keeps Android's foreground-location
            // indicator solidly lit and feeds the dot whichever fix lands first.
            grantCoarsePermission()

            provider.observe().test {
                assertThat(shadowManager.getLocationUpdateListeners(LocationManager.GPS_PROVIDER))
                    .hasSize(1)
                assertThat(shadowManager.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER))
                    .hasSize(1)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observe emits when a NETWORK fix is simulated`() =
        runTest {
            grantCoarsePermission()

            provider.observe().test {
                // Drive the listener directly. `ShadowLocationManager.simulateLocation` posts to
                // the Looper the listener was registered on, which on Robolectric needs
                // additional looper idling that's clunky to chain through Turbine — pulling the
                // registered listener and invoking `onLocationChanged` ourselves is the same
                // observable side effect, just synchronous.
                val listener =
                    shadowManager.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).single()
                listener.onLocationChanged(fixAt(FLINDERS_LAT, FLINDERS_LNG))
                assertThat(awaitItem()).isEqualTo(Coordinates(FLINDERS_LAT, FLINDERS_LNG))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observe emits when a GPS fix is simulated`() =
        runTest {
            grantCoarsePermission()

            provider.observe().test {
                val listener =
                    shadowManager.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).single()
                listener.onLocationChanged(fixAt(FED_LAT, FED_LNG))
                assertThat(awaitItem()).isEqualTo(Coordinates(FED_LAT, FED_LNG))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observe stays open when only one provider is disabled mid-stream`() =
        runTest {
            // Issue #127 — with both providers registered, dropping one shouldn't kill the dot.
            grantCoarsePermission()

            provider.observe().test {
                val networkListener =
                    shadowManager.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).single()
                networkListener.onProviderDisabled(LocationManager.NETWORK_PROVIDER)
                // Flow stays open — GPS is still feeding it. A GPS fix should still arrive.
                val gpsListener =
                    shadowManager.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).single()
                gpsListener.onLocationChanged(fixAt(FED_LAT, FED_LNG))
                assertThat(awaitItem()).isEqualTo(Coordinates(FED_LAT, FED_LNG))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observe completes when every provider is disabled mid-stream`() =
        runTest {
            grantCoarsePermission()

            provider.observe().test {
                val networkListener =
                    shadowManager.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).single()
                val gpsListener =
                    shadowManager.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).single()
                // Mimic `onProviderDisabled` being called by the system for both providers.
                // Last one out closes the flow.
                networkListener.onProviderDisabled(LocationManager.NETWORK_PROVIDER)
                gpsListener.onProviderDisabled(LocationManager.GPS_PROVIDER)
                awaitComplete()
            }
        }

    @Test
    fun `observe completes when no provider is enabled`() =
        runTest {
            grantCoarsePermission()
            // Disable both providers so the impl's "else -> close()" branch runs synchronously.
            shadowManager.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
            shadowManager.setProviderEnabled(LocationManager.GPS_PROVIDER, false)

            provider.observe().test {
                awaitComplete()
            }
        }

    @Test
    fun `observe completes when permission missing`() =
        runTest {
            // Deliberately no permission grant. The flow must complete cleanly — that's an
            // acceptance criterion (the UI side has no error branch to handle).
            provider.observe().test {
                awaitComplete()
            }
        }

    private fun grantCoarsePermission() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun fixAt(
        lat: Double,
        lng: Double,
    ): Location =
        Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = lat
            longitude = lng
            // Robolectric's `Location` constructor needs a non-zero accuracy for some shadow
            // setters; setting a sane value here keeps the fix realistic.
            accuracy = ACCURACY_M
        }

    private companion object {
        const val FLINDERS_LAT = -37.8183
        const val FLINDERS_LNG = 144.9671
        const val FED_LAT = -37.8180
        const val FED_LNG = 144.9690
        const val ACCURACY_M = 50f
    }
}

private const val SDK_PIN = 34
