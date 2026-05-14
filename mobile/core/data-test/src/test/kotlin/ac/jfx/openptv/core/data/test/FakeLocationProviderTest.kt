package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.testing.CoordinatesMother.Companion.federationSquare
import ac.jfx.openptv.core.testing.CoordinatesMother.Companion.flindersStreet
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Smoke tests for [FakeLocationProvider]. The fake is the test seam every feature ViewModel
 * test ends up depending on (via `@TestInstallIn`), so the production contract — seed-emit-
 * complete semantics — is worth pinning here even though `:core:data-test` is itself test code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeLocationProviderTest {
    @Test
    fun `lastKnown returns the seeded default`() =
        runTest {
            val provider = FakeLocationProvider()

            // Default is Melbourne CBD centroid (matches the auto-memory's geo fix command).
            assertThat(provider.lastKnown()).isNotNull()
        }

    @Test
    fun `seed updates lastKnown without emitting`() =
        runTest {
            val provider = FakeLocationProvider()
            val updated = federationSquare().build()

            provider.seed(updated)

            assertThat(provider.lastKnown()).isEqualTo(updated)
        }

    @Test
    fun `observe replays the current fix on subscribe`() =
        runTest {
            val provider = FakeLocationProvider()
            val seeded = flindersStreet().build()
            provider.seed(seeded)

            provider.observe().test {
                assertThat(awaitItem()).isEqualTo(seeded)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `emit pushes to active observers and updates lastKnown`() =
        runTest {
            val provider = FakeLocationProvider()
            val updated = federationSquare().build()

            provider.observe().test {
                // First emission is the seeded default.
                awaitItem()
                provider.emit(updated)
                assertThat(awaitItem()).isEqualTo(updated)
                assertThat(provider.lastKnown()).isEqualTo(updated)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `complete terminates all active observers`() =
        runTest {
            val provider = FakeLocationProvider()

            provider.observe().test {
                awaitItem()
                provider.complete()
                awaitComplete()
            }
        }
}
