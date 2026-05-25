package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.testing.CoordinatesMother
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for [followMeFabAction] — the pure projection from [NearbyUiState] to the
 * follow-me FAB's tap behaviour (#125, replaces the popup rationale dialog).
 *
 * The FAB is always visible. When permission isn't granted yet, we tint its icon with
 * `colorScheme.error` (the "GPS off" hint per #125) and rewire its tap to either launch the
 * system permission prompt or jump to app settings. When granted, the FAB recentres on the user
 * fix exactly as before. The branching is captured by [FollowMeFabAction]; this test pins each
 * variant.
 */
class FollowMeFabActionTest {
    @Test
    fun `Loaded state yields Recentre with a normal (non-error) tint`() {
        val state =
            NearbyUiState.Loaded(
                camera =
                    OpenPtvCameraState(
                        centre = CoordinatesMother.flindersStreet().build(),
                        zoom = NearbyViewModel.INITIAL_ZOOM,
                    ),
                pins = emptyList(),
                userLocation = CoordinatesMother.flindersStreet().build(),
                isFollowingUser = false,
                pendingSheet = SheetState.Closed,
                showEmptyHint = false,
            )

        assertThat(state.followMeFabAction()).isEqualTo(FollowMeFabAction.Recentre)
        assertThat(state.followMeFabAction().isPermissionAction).isFalse()
    }

    @Test
    fun `Loaded with a null fix still yields Recentre — the FAB is always visible (#125)`() {
        // Before #125 the FAB was hidden when `userLocation == null`. Now the FAB always shows;
        // a Loaded state with no fix yet still recentres (the camera follows the next fix).
        val state =
            NearbyUiState.Loaded(
                camera =
                    OpenPtvCameraState(
                        centre = CoordinatesMother.flindersStreet().build(),
                        zoom = NearbyViewModel.INITIAL_ZOOM,
                    ),
                pins = emptyList(),
                userLocation = null,
                isFollowingUser = false,
                pendingSheet = SheetState.Closed,
                showEmptyHint = false,
            )

        assertThat(state.followMeFabAction()).isEqualTo(FollowMeFabAction.Recentre)
    }

    @Test
    fun `PermissionUnasked yields RequestPermission with the error tint`() {
        // First launch — tap launches the system permission prompt in place of the old rationale
        // dialog (#120, #125). The icon is tinted `colorScheme.error` as the "GPS off" hint.
        val action = NearbyUiState.PermissionUnasked.followMeFabAction()

        assertThat(action).isEqualTo(FollowMeFabAction.RequestPermission)
        assertThat(action.isPermissionAction).isTrue()
    }

    @Test
    fun `PermissionDenied yields OpenAppSettings with the error tint`() {
        // Once the user has actively denied, Android suppresses re-prompts, so the only way back
        // is the app's system-settings page; the FAB jumps the user straight there.
        val state =
            NearbyUiState.PermissionDenied(
                camera =
                    OpenPtvCameraState(
                        centre = NearbyViewModel.MELBOURNE_CBD,
                        zoom = NearbyViewModel.INITIAL_ZOOM,
                    ),
                pins = emptyList(),
            )

        val action = state.followMeFabAction()

        assertThat(action).isEqualTo(FollowMeFabAction.OpenAppSettings)
        assertThat(action.isPermissionAction).isTrue()
    }
}
