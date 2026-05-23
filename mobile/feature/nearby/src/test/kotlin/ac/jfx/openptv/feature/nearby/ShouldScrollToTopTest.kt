package ac.jfx.openptv.feature.nearby

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for [shouldScrollToTop] — the pure projection from the bottom sheet's [SheetValue] to
 * whether the nearby-stops list should snap back to row 0 on row-list change (issue #130).
 *
 * The collapsed/partial sheet exposes only the top row, so when the underlying list reorders
 * (the user moved and a different stop is now closest) we re-snap so the peek surface shows the
 * current closest stop. When the sheet is fully expanded the user is browsing — leave them be.
 */
@OptIn(ExperimentalMaterial3Api::class)
class ShouldScrollToTopTest {
    @Test
    fun `PartiallyExpanded scrolls to top — the peek surface must always show the closest stop`() {
        assertThat(shouldScrollToTop(SheetValue.PartiallyExpanded)).isTrue()
    }

    @Test
    fun `Hidden scrolls to top — defensive, the sheet has skipHiddenState but the helper stays total`() {
        assertThat(shouldScrollToTop(SheetValue.Hidden)).isTrue()
    }

    @Test
    fun `Expanded does not scroll — user is browsing further-away stops, keep their position`() {
        assertThat(shouldScrollToTop(SheetValue.Expanded)).isFalse()
    }
}
