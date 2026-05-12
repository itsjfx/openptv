/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.feature.setup

/**
 * UI state for the first-run setup screen.
 *
 * [serverChoice] tracks whether the user wants to use the bundled default URL or supply their
 * own. [customUrl] holds the in-progress text-field value (only meaningful when
 * `serverChoice == Custom`). [consentAccepted] mirrors the consent checkbox.
 *
 * [canContinue] is `true` only when the user has both chosen a server and ticked the consent
 * box; the "Accept & continue" CTA observes it.
 */
data class SetupUiState(
    val defaultUrl: String,
    val serverChoice: ServerChoice = ServerChoice.Default,
    val customUrl: String = "",
    val consentAccepted: Boolean = false,
) {
    val effectiveUrl: String =
        when (serverChoice) {
            ServerChoice.Default -> defaultUrl
            ServerChoice.Custom -> customUrl
        }

    val canContinue: Boolean = consentAccepted && effectiveUrl.isNotBlank()
}

enum class ServerChoice {
    Default,
    Custom,
}
