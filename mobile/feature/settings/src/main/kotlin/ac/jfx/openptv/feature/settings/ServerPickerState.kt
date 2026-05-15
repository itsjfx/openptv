package ac.jfx.openptv.feature.settings

/**
 * State for the server-picker dialog opened from the Settings screen. Mirrors the shape of
 * `SetupUiState` (in `:app`'s `feature.setup` package) so the two surfaces describe the same
 * thing — first-run picker and post-onboarding picker share the validation and choice model
 * even though the dialog UX is intentionally different (no consent checkbox, no
 * "Accept & continue" CTA — the user already accepted at onboarding).
 *
 * Validation parity is the load-bearing bit: [canSave] uses the same `effectiveUrl.isNotBlank()`
 * rule [SetupUiState] uses for `canContinue`, so a value the onboarding screen would accept is
 * also a value the settings dialog accepts (and vice versa). URL normalisation (trailing slash
 * etc.) lives in `SettingsRepositoryImpl.setBackendBaseUrl` and applies to writes from either
 * surface — the picker doesn't have its own normaliser.
 *
 * [defaultUrl] is seeded from `BuildConfig.BACKEND_BASE_URL` (via the repository's first emit on
 * fresh install, or whatever the user picked at onboarding) and shown as the subtitle of the
 * "Default" radio row so users can see what they'd be picking. [currentUrl] is the URL the
 * dialog opens at — used to seed [customUrl] when the user has previously chosen a non-default
 * server, so re-opening the dialog shows their last value rather than an empty field.
 */
internal data class ServerPickerState(
    val defaultUrl: String,
    val currentUrl: String,
    val choice: ServerChoice = ServerChoice.Default,
    val customUrl: String = "",
) {
    val effectiveUrl: String =
        when (choice) {
            ServerChoice.Default -> defaultUrl
            ServerChoice.Custom -> customUrl
        }

    /**
     * `true` iff the chosen URL is non-blank — the same rule [SetupUiState.canContinue] uses
     * (minus the consent gate, which only applies on first run). Trim before checking so a
     * field of pure whitespace is rejected the same way the onboarding screen would reject it.
     */
    val canSave: Boolean = effectiveUrl.isNotBlank() && effectiveUrl.trim().isNotEmpty()
}

/**
 * Two-case enum mirroring the onboarding picker's `ServerChoice`. Duplicated here rather than
 * shared from `:app` because `:feature:settings` deliberately doesn't depend on `:app` — and
 * the shape is small enough that duplicating is cheaper than promoting the type to a shared
 * module just to avoid the duplication.
 */
internal enum class ServerChoice {
    Default,
    Custom,
}
