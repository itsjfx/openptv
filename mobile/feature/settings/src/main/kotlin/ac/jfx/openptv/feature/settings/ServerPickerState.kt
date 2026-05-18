package ac.jfx.openptv.feature.settings

/**
 * State for the server-picker. Drives the three-radio picker body rendered by
 * [ServerPickerContent], which is shared between the Settings dialog and the first-run setup
 * screen (`:app`'s `SetupScreen` wraps the same composable inline). One shape across both
 * surfaces means a change to the picker's affordance or validation lands in lock-step.
 *
 *  - **Default** — talk to the bundled maintainer-operated proxy URL.
 *  - **Custom**  — talk to a user-supplied proxy URL.
 *  - **DirectPtv** — sign requests on-device with the user's PTV `devId` + `apiKey` and call
 *    PTV's host directly, skipping the proxy.
 *
 * URL normalisation (trailing slash etc.) lives in `SettingsRepositoryImpl.setBackendBaseUrl` /
 * `completeSetup` and applies to writes from either surface — the picker doesn't have its own
 * normaliser.
 *
 * [defaultUrl] is seeded from `BuildConfig.BACKEND_BASE_URL` (via the repository's first emit on
 * fresh install, or whatever the user picked at onboarding) and used by the Settings picker as
 * the URL written when the user lands on the Default radio. The setup-screen wrapping
 * deliberately doesn't surface this URL to the user — the proxy address is implementation
 * detail at first-run, not something to invite editing. [currentUrl] is the URL the dialog
 * opens at — used by the Settings picker to seed [customUrl] when the user has previously
 * chosen a non-default server. The setup wrapping passes an empty string because nothing has
 * been persisted yet. [devId] / [apiKey] are seeded from persisted values on the Settings side,
 * empty on the setup side.
 */
data class ServerPickerState(
    val defaultUrl: String,
    val currentUrl: String,
    val choice: ServerChoice = ServerChoice.Default,
    val customUrl: String = "",
    val devId: String = "",
    val apiKey: String = "",
    /**
     * Flipped to `true` by the screen when the user taps the submit affordance (Settings dialog
     * "Save" or setup screen "Continue") while [canSave] is `false`. Drives [customUrlError],
     * [devIdError], and [apiKeyError] so the offending fields paint red with a "Required"
     * supporting line instead of the previous silent-disable. Cleared back to `false` whenever
     * the user types into any field (see `ServerPickerContent`'s `onValueChange` callers).
     */
    val showValidationErrors: Boolean = false,
) {
    /**
     * The proxy URL the picker would write through on Save when [choice] is a proxy choice.
     * Unused when [choice] is [ServerChoice.DirectPtv] — direct mode doesn't change the proxy
     * URL, only flips the direct-mode flag, so the user can keep their previous proxy URL
     * available if they later flip back.
     */
    val effectiveUrl: String =
        when (choice) {
            ServerChoice.Default -> defaultUrl
            ServerChoice.Custom -> customUrl
            ServerChoice.DirectPtv -> currentUrl
        }

    /**
     * `true` iff the chosen option is committable:
     *
     *  - **Default**: always — the bundled URL is known-good.
     *  - **Custom**: the typed URL is non-blank.
     *  - **DirectPtv**: both `devId` and `apiKey` are non-blank — the resolver would otherwise
     *    silently fall back to proxy mode (`SettingsPtvUrlResolver:34`), which would surprise
     *    the user who just hit Save.
     *
     * The setup screen's `SetupUiState.canContinue` delegates to this same flag, so the
     * onboarding Continue CTA is gated by the exact same rule as the Settings dialog's Save.
     * Trim before checking so a field of pure whitespace is rejected at the same point both
     * surfaces would reject it.
     */
    val canSave: Boolean =
        when (choice) {
            ServerChoice.Default -> defaultUrl.isNotBlank()
            ServerChoice.Custom -> customUrl.trim().isNotEmpty()
            ServerChoice.DirectPtv -> devId.trim().isNotEmpty() && apiKey.trim().isNotEmpty()
        }

    /**
     * Per-field error flags consumed by `OutlinedTextField.isError` in `ServerPickerContent`.
     * Each is `true` iff [showValidationErrors] is on, the field is the one that belongs to the
     * currently-selected [choice], and the field's value is blank. Computed via `get()` so the
     * data-class `copy()` still drives all state mutations.
     */
    val customUrlError: Boolean
        get() = showValidationErrors && choice == ServerChoice.Custom && customUrl.trim().isEmpty()

    val devIdError: Boolean
        get() = showValidationErrors && choice == ServerChoice.DirectPtv && devId.trim().isEmpty()

    val apiKeyError: Boolean
        get() = showValidationErrors && choice == ServerChoice.DirectPtv && apiKey.trim().isEmpty()
}

/**
 * Three-case picker selection, shared between the Settings dialog and the first-run setup
 * screen (`:app` depends on `:feature:settings` and reuses this enum directly). The setup
 * surface now offers all three options too so users who want to go direct from the start can,
 * matching the post-onboarding Settings picker.
 */
enum class ServerChoice {
    Default,
    Custom,
    DirectPtv,
}
