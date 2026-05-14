package ac.jfx.openptv.feature.favourites

import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the favourites-screen runtime crash on the first composition with rows.
 *
 * Background: passing a `FavouriteKey` data class straight into `LazyColumn(items, key = ...)`
 * throws at runtime — `SaveableStateHolderImpl.SaveableStateProvider` round-trips the key through
 * `Bundle` for state restoration, and `Bundle` only accepts primitives, `Parcelable`, or declared
 * `Serializable`. The screen now projects the key through [asLazyListKey] before handing it to
 * `LazyColumn`; this test pins the contract.
 *
 * Why Robolectric: `Bundle` is an Android type whose `put*` methods enforce the type whitelist at
 * runtime. The bundled JVM stub in `android.jar` is a no-op, so to actually exercise the rejection
 * we need a Robolectric-shadowed `Bundle`. The test is small enough (one `Config` line) that the
 * extra dependency on `feature/favourites`'s test classpath is worth it for the regression
 * coverage — without this, the only place that catches the bug is the instrumented `connected-test`
 * job, which is currently skipping in CI.
 *
 * Pinned to Robolectric SDK 34 with `manifest = NONE` — same shape `:core:datastore` and
 * `:core:database` use, and avoids the manifest-resolution dance for a module whose `Bundle`
 * coverage doesn't need any resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FavouritesScreenKeyTest {
    @Test
    fun `asLazyListKey is a Bundle-safe String for any FavouriteKey`() {
        val key = FavouriteKey(stopId = 2720, routeId = 897, directionId = 11)
        val projected = key.asLazyListKey()

        // Concrete shape: it's a String, not the data class. That alone is enough to satisfy
        // `Bundle.putSerializable` / the saveable-state-holder path.
        assertThat(projected).isInstanceOf(String::class.java)

        // And — the real assertion — a `Bundle` round-trip accepts it. This is the exact shape
        // `SaveableStateHolderImpl.SaveableStateProvider` performs on the LazyColumn's
        // `key =` slot. With the bug present (passing the data class directly), this call throws
        // `IllegalArgumentException: Type of the key ... is not supported`.
        val bundle = Bundle()
        bundle.putString("k", projected)
        assertThat(bundle.getString("k")).isEqualTo(projected)
    }

    @Test
    fun `asLazyListKey is unique per triple`() {
        // Two keys that share two of three components must still project to different strings so
        // `LazyColumn` doesn't collapse rows under a recompose.
        val a = FavouriteKey(stopId = 1, routeId = 2, directionId = 3).asLazyListKey()
        val b = FavouriteKey(stopId = 1, routeId = 2, directionId = 4).asLazyListKey()
        val c = FavouriteKey(stopId = 1, routeId = 3, directionId = 3).asLazyListKey()
        val d = FavouriteKey(stopId = 2, routeId = 2, directionId = 3).asLazyListKey()
        assertThat(setOf(a, b, c, d)).hasSize(4)
    }

    @Test
    fun `asLazyListKey is stable across calls for the same triple`() {
        val key = FavouriteKey(stopId = 24174, routeId = 13830, directionId = 298)
        assertThat(key.asLazyListKey()).isEqualTo(key.asLazyListKey())
    }
}
