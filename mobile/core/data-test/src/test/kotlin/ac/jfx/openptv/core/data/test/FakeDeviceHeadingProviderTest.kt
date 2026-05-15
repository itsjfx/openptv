package ac.jfx.openptv.core.data.test

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Smoke tests for [FakeDeviceHeadingProvider]. Sibling to [FakeLocationProviderTest] — pins the
 * emit / complete semantics every consumer (the nearby map screen, future compass-aware
 * features) relies on through `@TestInstallIn`.
 *
 * Unlike the location fake, this one doesn't replay on subscribe — the production sensor flow
 * has no concept of "last known bearing", so the fake matches that contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeDeviceHeadingProviderTest {
    @Test
    fun `emit pushes to active observers`() =
        runTest {
            val provider = FakeDeviceHeadingProvider()

            provider.observe().test {
                provider.emit(NORTH_NORTH_EAST)
                assertThat(awaitItem()).isEqualTo(NORTH_NORTH_EAST)
                provider.emit(EAST)
                assertThat(awaitItem()).isEqualTo(EAST)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `complete terminates all active observers`() =
        runTest {
            val provider = FakeDeviceHeadingProvider()

            provider.observe().test {
                provider.complete()
                awaitComplete()
            }
        }

    @Test
    fun `observers subscribed before emit see all values in order`() =
        runTest {
            val provider = FakeDeviceHeadingProvider()

            provider.observe().test {
                provider.emit(NORTH)
                provider.emit(EAST)
                provider.emit(SOUTH)
                provider.emit(WEST)
                assertThat(awaitItem()).isEqualTo(NORTH)
                assertThat(awaitItem()).isEqualTo(EAST)
                assertThat(awaitItem()).isEqualTo(SOUTH)
                assertThat(awaitItem()).isEqualTo(WEST)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        const val NORTH: Float = 0f
        const val NORTH_NORTH_EAST: Float = 22.5f
        const val EAST: Float = 90f
        const val SOUTH: Float = 180f
        const val WEST: Float = 270f
    }
}
