package ac.jfx.openptv.feature.setup

import ac.jfx.openptv.R
import ac.jfx.openptv.feature.settings.ServerPickerContent
import ac.jfx.openptv.feature.settings.ServerPickerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * First-run setup. Lets the user pick the bundled default proxy, a custom proxy, or sign
 * requests on-device with their own PTV API key, and gates the rest of the app behind explicit
 * consent. The radio rows + conditional fields are shared with the Settings server-picker
 * dialog via `:feature:settings`'s `ServerPickerContent` so both surfaces render the exact
 * same affordance, copy, and validation.
 */
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SetupScreenContent(
        state = state,
        onPickerStateChange = viewModel::onPickerStateChanged,
        onConsentToggled = viewModel::onConsentToggled,
        onContinue = { viewModel.completeSetup(onSetupComplete) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupScreenContent(
    state: SetupUiState,
    onPickerStateChange: (ServerPickerState) -> Unit,
    onConsentToggled: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.setup_title)) })
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.setup_intro),
                style = MaterialTheme.typography.bodyMedium,
            )

            // Shared with the Settings server-picker dialog. Same three radio rows (Default /
            // Custom / Direct PTV), same conditional URL + credential fields, same copy — so
            // the first-run surface and the post-onboarding surface are byte-for-byte
            // identical at the picker level.
            ServerPickerContent(
                state = state.pickerState,
                onStateChange = onPickerStateChange,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.consentAccepted,
                            onClick = { onConsentToggled(!state.consentAccepted) },
                            role = Role.Checkbox,
                        )
                        .testTag(TestTagConsentRow)
                        .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.consentAccepted,
                    onCheckedChange = null,
                )
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Text(
                    text = stringResource(R.string.setup_consent_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Button(
                // Tappable as soon as consent is accepted. Validation-on-tap then flips the
                // picker's error flag (mirrors the Settings dialog Save behaviour) so blank
                // required fields paint red instead of silently disabling Continue. Consent is
                // a hard prerequisite — not a typo-able field — so the button stays disabled
                // until that's ticked.
                onClick = {
                    if (state.canContinue) {
                        onContinue()
                    } else {
                        onPickerStateChange(state.pickerState.copy(showValidationErrors = true))
                    }
                },
                enabled = state.consentAccepted,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(TestTagContinueButton),
            ) {
                Text(stringResource(R.string.setup_continue))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

internal const val TestTagConsentRow: String = "setup-consent-row"
internal const val TestTagContinueButton: String = "setup-continue-button"
