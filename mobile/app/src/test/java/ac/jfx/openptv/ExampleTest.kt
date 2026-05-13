package ac.jfx.openptv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Confirms the JUnit 4 + Truth test harness is wired. Once Phase 02 lands the multi-module split,
 * proper unit tests will live in `:core:*` and `:feature:*` modules; this file is intentionally trivial.
 */
class ExampleTest {
    @Test
    fun `harness is wired`() {
        assertThat(2 + 2).isEqualTo(4)
    }
}
