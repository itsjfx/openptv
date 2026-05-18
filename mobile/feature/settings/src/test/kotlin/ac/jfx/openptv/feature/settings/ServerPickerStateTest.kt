package ac.jfx.openptv.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the validation rules of the shared server picker. The same [ServerPickerState] is used
 * by both the Settings dialog and the first-run setup screen (which wraps `canSave` inside
 * `SetupUiState.canContinue`), so a change here lands on both surfaces — that's exactly the
 * single-source-of-truth this PR introduces. The DirectPtv case pins the dev_id + api_key
 * non-blank contract that `SettingsPtvUrlResolver` relies on to actually take the direct-sign
 * path.
 *
 * `effectiveUrl` resolution and `canSave` are pure-function — direct construction beats
 * pulling in an Object Mother for a six-field type with an obvious default.
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
        // Setup's `SetupUiState.canContinue` delegates to `canSave`, so pure whitespace must
        // be rejected here for both surfaces to reject it identically.
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

    @Test
    fun `direct PTV choice with both credentials canSave`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.DirectPtv,
                devId = "3000176",
                apiKey = "9c132d31-6a30-4cac-8d8b-8a1970834799",
            )

        assertThat(state.canSave).isTrue()
    }

    @Test
    fun `direct PTV choice with blank devId cannot save`() {
        // The resolver silently falls back to proxy if either credential is blank
        // (`SettingsPtvUrlResolver:34`). Block save instead so the user doesn't get a
        // confusing "I picked Direct PTV but searches still go via the proxy" outcome.
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.DirectPtv,
                devId = "",
                apiKey = "9c132d31-6a30-4cac-8d8b-8a1970834799",
            )

        assertThat(state.canSave).isFalse()
    }

    @Test
    fun `direct PTV choice with blank apiKey cannot save`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.DirectPtv,
                devId = "3000176",
                apiKey = "",
            )

        assertThat(state.canSave).isFalse()
    }

    @Test
    fun `direct PTV choice with whitespace-only credentials cannot save`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.DirectPtv,
                devId = "   ",
                apiKey = "  \t ",
            )

        assertThat(state.canSave).isFalse()
    }

    // ------------------------------------------------------------------------
    // Per-field error flags consumed by `OutlinedTextField.isError`. Each is gated on three
    // things in concert: `showValidationErrors` must be on, the field must belong to the
    // currently-selected choice, and the field's value must be blank. The matrix below pins
    // each gate against the others so a regression — e.g. flag flipped on but errors leak
    // into the wrong choice's fields — fails here, not in a screenshot review.
    // ------------------------------------------------------------------------

    @Test
    fun `customUrlError is false when validation flag is off`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.Custom,
                customUrl = "",
                showValidationErrors = false,
            )

        assertThat(state.customUrlError).isFalse()
    }

    @Test
    fun `customUrlError is true when validation flag is on and URL is blank`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.Custom,
                customUrl = "",
                showValidationErrors = true,
            )

        assertThat(state.customUrlError).isTrue()
    }

    @Test
    fun `customUrlError is true when validation flag is on and URL is whitespace`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.Custom,
                customUrl = "   ",
                showValidationErrors = true,
            )

        assertThat(state.customUrlError).isTrue()
    }

    @Test
    fun `customUrlError is false when validation flag is on but URL is non-blank`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.Custom,
                customUrl = "http://custom.local/api/v3/",
                showValidationErrors = true,
            )

        assertThat(state.customUrlError).isFalse()
    }

    @Test
    fun `customUrlError is false when choice is not Custom`() {
        // Even with the flag on and a blank customUrl, the error only paints the field that
        // belongs to the currently-selected radio choice. Default doesn't render the field.
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.Default,
                customUrl = "",
                showValidationErrors = true,
            )

        assertThat(state.customUrlError).isFalse()
    }

    @Test
    fun `devIdError is false when validation flag is off`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.DirectPtv,
                devId = "",
                apiKey = "",
                showValidationErrors = false,
            )

        assertThat(state.devIdError).isFalse()
    }

    @Test
    fun `devIdError is true when validation flag is on and devId is blank`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.DirectPtv,
                devId = "",
                apiKey = "9c132d31-6a30-4cac-8d8b-8a1970834799",
                showValidationErrors = true,
            )

        assertThat(state.devIdError).isTrue()
    }

    @Test
    fun `devIdError is false when validation flag is on but devId is non-blank`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.DirectPtv,
                devId = "3000176",
                apiKey = "",
                showValidationErrors = true,
            )

        assertThat(state.devIdError).isFalse()
    }

    @Test
    fun `devIdError is false when choice is not DirectPtv`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.Custom,
                devId = "",
                showValidationErrors = true,
            )

        assertThat(state.devIdError).isFalse()
    }

    @Test
    fun `apiKeyError is false when validation flag is off`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.DirectPtv,
                devId = "",
                apiKey = "",
                showValidationErrors = false,
            )

        assertThat(state.apiKeyError).isFalse()
    }

    @Test
    fun `apiKeyError is true when validation flag is on and apiKey is blank`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.DirectPtv,
                devId = "3000176",
                apiKey = "",
                showValidationErrors = true,
            )

        assertThat(state.apiKeyError).isTrue()
    }

    @Test
    fun `apiKeyError is false when validation flag is on but apiKey is non-blank`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.DirectPtv,
                devId = "",
                apiKey = "9c132d31-6a30-4cac-8d8b-8a1970834799",
                showValidationErrors = true,
            )

        assertThat(state.apiKeyError).isFalse()
    }

    @Test
    fun `apiKeyError is false when choice is not DirectPtv`() {
        val state =
            ServerPickerState(
                defaultUrl = "http://default.local/api/v3/",
                currentUrl = "http://default.local/api/v3/",
                choice = ServerChoice.Default,
                apiKey = "",
                showValidationErrors = true,
            )

        assertThat(state.apiKeyError).isFalse()
    }
}
