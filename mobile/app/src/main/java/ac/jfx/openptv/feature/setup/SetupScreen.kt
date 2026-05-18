package ac.jfx.openptv.feature.setup

import ac.jfx.openptv.R
import ac.jfx.openptv.feature.settings.ServerPickerContent
import ac.jfx.openptv.feature.settings.ServerPickerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * First-run setup. Lets the user pick the bundled default proxy, a custom proxy, or sign
 * requests on-device with their own PTV API key. The radio rows + conditional fields are shared
 * with the Settings server-picker dialog via `:feature:settings`'s `ServerPickerContent` so both
 * surfaces render the exact same affordance, copy, and validation.
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
        onContinue = { viewModel.completeSetup(onSetupComplete) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupScreenContent(
    state: SetupUiState,
    onPickerStateChange: (ServerPickerState) -> Unit,
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

            ServerPickerContent(
                state = state.pickerState,
                onStateChange = onPickerStateChange,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    if (state.canContinue) {
                        onContinue()
                    } else {
                        onPickerStateChange(state.pickerState.copy(showValidationErrors = true))
                    }
                },
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

internal const val TestTagContinueButton: String = "setup-continue-button"
