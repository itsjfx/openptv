package ac.jfx.openptv.core.designsystem

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose tests for [LocationPermissionRationale]. Pinned to Robolectric SDK 34 because
 * Robolectric 4.14.x ships SDK 34 jars (same pin as `:core:datastore`'s composition tests).
 *
 * Scope: confirm the title / body copy renders, the Allow button calls `onConfirm`, and the
 * Not now button calls `onDismiss`. The actual permission-launch wiring is the caller's
 * responsibility — this dialog deliberately knows nothing about `rememberLauncherForActivityResult`.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [SDK_PIN])
class LocationPermissionRationaleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun renders_title_and_body() {
        composeRule.setContent {
            OpenPtvTheme(themeMode = ThemeMode.System) {
                LocationPermissionRationale(onConfirm = {}, onDismiss = {})
            }
        }

        composeRule.onNodeWithText("Use your location?").assertIsDisplayed()
        composeRule
            .onNodeWithText("OpenPTV uses your location to show stops near you.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun confirm_button_invokes_onConfirm() {
        var confirmed = false
        composeRule.setContent {
            OpenPtvTheme(themeMode = ThemeMode.System) {
                LocationPermissionRationale(onConfirm = { confirmed = true }, onDismiss = {})
            }
        }

        composeRule.onNodeWithText("Allow").performClick()

        assertThat(confirmed).isTrue()
    }

    @Test
    fun dismiss_button_invokes_onDismiss() {
        var dismissed = false
        composeRule.setContent {
            OpenPtvTheme(themeMode = ThemeMode.System) {
                LocationPermissionRationale(onConfirm = {}, onDismiss = { dismissed = true })
            }
        }

        composeRule.onNodeWithText("Not now").performClick()

        assertThat(dismissed).isTrue()
    }
}

private const val SDK_PIN = 34
