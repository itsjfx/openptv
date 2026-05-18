package ac.jfx.openptv.feature.setup

import ac.jfx.openptv.feature.settings.ServerChoice
import ac.jfx.openptv.feature.settings.ServerPickerState

/**
 * UI state for the first-run setup screen.
 *
 * Wraps a [ServerPickerState] — the same state object the Settings server-picker dialog
 * drives — so the shared `ServerPickerContent` composable renders identically across both
 * surfaces.
 *
 * `canContinue` delegates to [ServerPickerState.canSave] — Default is always committable,
 * Custom requires a non-blank URL, Direct PTV requires both credentials — so any future
 * tightening of the picker's rules lands here automatically.
 */
data class SetupUiState(
    val pickerState: ServerPickerState,
) {
    val canContinue: Boolean = pickerState.canSave

    companion object {
        /**
         * Initial state seeded by the ViewModel. `defaultUrl` is filled in from the persisted
         * `BuildConfig.BACKEND_BASE_URL` once the init job reads from the repository;
         * `currentUrl` is empty because nothing's been persisted yet on first run.
         */
        fun initial(defaultUrl: String = ""): SetupUiState =
            SetupUiState(
                pickerState =
                    ServerPickerState(
                        defaultUrl = defaultUrl,
                        currentUrl = "",
                        choice = ServerChoice.Default,
                    ),
            )
    }
}
