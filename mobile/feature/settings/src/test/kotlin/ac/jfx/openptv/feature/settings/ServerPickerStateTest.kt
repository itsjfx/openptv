package ac.jfx.openptv.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the validation parity between the Settings server-picker dialog and the first-run
 * onboarding picker. The shape mirrors the cases already covered by `SetupViewModelTest` /
 * `SetupUiState` so a change to the validation rule on either side surfaces here as a failing
 * test rather than a runtime divergence.
 *
 * `effectiveUrl` resolution and `canSave` are pure-function — direct construction beats
 * pulling in an Object Mother for a five-field type with an obvious default.
 */
class ServerPickerStateTest {
    @Test
    fun `default choice resolves effectiveUrl to defaultUrl`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
            )

        assertThat(state.effectiveUrl).isEqualTo("http://default.local/api/v3/")
    }

    @Test
    fun `custom choice resolves effectiveUrl to customUrl`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.Custom,
                customUrl = "http://custom.local/api/v3/",
            )

        assertThat(state.effectiveUrl).isEqualTo("http://custom.local/api/v3/")
    }

    @Test
    fun `default choice with non-blank default URL canSave`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "",
            )

        assertThat(state.canSave).isTrue()
    }

    @Test
    fun `custom choice with empty URL cannot save`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.Custom,
                customUrl = "",
            )

        assertThat(state.canSave).isFalse()
    }

    @Test
    fun `custom choice with whitespace-only URL cannot save`() {
        // Mirrors `SetupUiState.canContinue`'s `effectiveUrl.isNotBlank()` check — pure
        // whitespace must be rejected at the same point both surfaces would reject it.
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.Custom,
                customUrl = "   ",
            )

        assertThat(state.canSave).isFalse()
    }

    @Test
    fun `custom choice with non-blank URL canSave`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.Custom,
                customUrl = "http://custom.local/api/v3/",
            )

        assertThat(state.canSave).isTrue()
    }
}
