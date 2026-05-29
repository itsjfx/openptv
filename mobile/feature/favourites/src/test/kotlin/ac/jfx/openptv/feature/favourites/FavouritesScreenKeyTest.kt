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
 * `Serializable`. The screen projects the key through [asLazyListKey] before handing it to
 * `LazyColumn`; this test pins the contract.
 *
 * Pinned to Robolectric SDK 34 with `manifest = NONE`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FavouritesScreenKeyTest {
    @Test
    fun `asLazyListKey is a Bundle-safe String for any FavouriteKey`() {
        val key = FavouriteKey(stopId = 2720, destinationKey = "north coburg")
        val projected = key.asLazyListKey()

        assertThat(projected).isInstanceOf(String::class.java)

        val bundle = Bundle()
        bundle.putString("k", projected)
        assertThat(bundle.getString("k")).isEqualTo(projected)
    }

    @Test
    fun `asLazyListKey is unique per pair`() {
        val a = FavouriteKey(stopId = 1, destinationKey = "city").asLazyListKey()
        val b = FavouriteKey(stopId = 1, destinationKey = "frankston").asLazyListKey()
        val c = FavouriteKey(stopId = 2, destinationKey = "city").asLazyListKey()
        assertThat(setOf(a, b, c)).hasSize(3)
    }

    @Test
    fun `asLazyListKey is stable across calls for the same pair`() {
        val key = FavouriteKey(stopId = 24174, destinationKey = "north coburg")
        assertThat(key.asLazyListKey()).isEqualTo(key.asLazyListKey())
    }

    @Test
    fun `asLazyListKey delimiter does not collide with dotted destination names`() {
        // PTV returns destination strings like "St. Kilda" — the delimiter must not be `.`,
        // because a destination key may contain dots and `(stopId, "st.kilda")` should never
        // collide with `(stopId.st, "kilda")` or similar reshufflings. `|` is the delimiter
        // and doesn't appear in PTV destination strings.
        val a = FavouriteKey(stopId = 11, destinationKey = "st. kilda").asLazyListKey()
        val b = FavouriteKey(stopId = 11, destinationKey = "st kilda").asLazyListKey()
        assertThat(a).isNotEqualTo(b)
        assertThat(a).contains("|")
    }
}
