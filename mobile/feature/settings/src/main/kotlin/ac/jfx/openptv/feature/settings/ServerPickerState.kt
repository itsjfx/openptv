package ac.jfx.openptv.feature.settings

/**
 * State for the server-picker dialog opened from the Settings screen. Mirrors the shape of
 * `SetupUiState` (in `:app`'s `feature.setup` package) for the proxy choices, and extends it
 * with a third [ServerChoice.DirectPtv] option (issue #102 / PR #113 feedback) so users can
 * pick between three connection modes from a single seam:
 *
 *  - **Default** — talk to the bundled maintainer-operated proxy URL.
 *  - **Custom**  — talk to a user-supplied proxy URL.
 *  - **DirectPtv** — sign requests on-device with the user's PTV `devId` + `apiKey` and call
 *    PTV's host directly, skipping the proxy.
 *
 * Validation parity with onboarding is the load-bearing bit for the two proxy choices: a value
 * the onboarding screen would accept is also a value this dialog accepts (and vice versa). URL
 * normalisation (trailing slash etc.) lives in `SettingsRepositoryImpl.setBackendBaseUrl` and
 * applies to writes from either surface — the picker doesn't have its own normaliser.
 *
 * [defaultUrl] is seeded from `BuildConfig.BACKEND_BASE_URL` (via the repository's first emit on
 * fresh install, or whatever the user picked at onboarding) and shown as the subtitle of the
 * "Default" radio row so users can see what they'd be picking. [currentUrl] is the URL the
 * dialog opens at — used to seed [customUrl] when the user has previously chosen a non-default
 * server, so re-opening the dialog shows their last value rather than an empty field. [devId] /
 * [apiKey] are seeded from the persisted values so the user sees what they previously entered.
 */
data class ServerPickerState(
    val defaultUrl: String,
    val currentUrl: String,
    val choice: ServerChoice = ServerChoice.Default,
    val customUrl: String = "",
    val devId: String = "",
    val apiKey: String = "",
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
     *  - **Custom**: the typed URL is non-blank (matches `SetupUiState.canContinue`).
     *  - **DirectPtv**: both `devId` and `apiKey` are non-blank — the resolver would otherwise
     *    silently fall back to proxy mode (`SettingsPtvUrlResolver:34`), which would surprise
     *    the user who just hit Save.
     *
     * Trim before checking so a field of pure whitespace is rejected the same way the
     * onboarding screen would reject it.
     */
    val canSave: Boolean =
        when (choice) {
            ServerChoice.Default -> defaultUrl.isNotBlank()
            ServerChoice.Custom -> customUrl.trim().isNotEmpty()
            ServerChoice.DirectPtv -> devId.trim().isNotEmpty() && apiKey.trim().isNotEmpty()
        }
}

/**
 * Three-case picker selection. The first two mirror the onboarding picker's `ServerChoice`
 * (duplicated rather than shared because `:feature:settings` deliberately doesn't depend on
 * `:app`); [DirectPtv] is settings-only — the first-run flow never offers it because the user
 * hasn't yet obtained a PTV key.
 */
enum class ServerChoice {
    Default,
    Custom,
    DirectPtv,
}
