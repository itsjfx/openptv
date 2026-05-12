package ac.jfx.openptv.feature.setup

import ac.jfx.openptv.R
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
 * First-run setup. Lets the user pick the bundled default proxy or a custom one and gates the
 * rest of the app behind explicit consent.
 */
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SetupScreenContent(
        state = state,
        onServerChoiceChanged = viewModel::onServerChoiceChanged,
        onCustomUrlChanged = viewModel::onCustomUrlChanged,
        onConsentToggled = viewModel::onConsentToggled,
        onContinue = { viewModel.completeSetup(onSetupComplete) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupScreenContent(
    state: SetupUiState,
    onServerChoiceChanged: (ServerChoice) -> Unit,
    onCustomUrlChanged: (String) -> Unit,
    onConsentToggled: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.setup_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
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

            ServerChoiceRow(
                choice = ServerChoice.Default,
                selected = state.serverChoice == ServerChoice.Default,
                titleRes = R.string.setup_default_title,
                bodyRes = R.string.setup_default_body,
                detail = state.defaultUrl,
                onClick = { onServerChoiceChanged(ServerChoice.Default) },
            )

            ServerChoiceRow(
                choice = ServerChoice.Custom,
                selected = state.serverChoice == ServerChoice.Custom,
                titleRes = R.string.setup_custom_title,
                bodyRes = R.string.setup_custom_body,
                detail = null,
                onClick = { onServerChoiceChanged(ServerChoice.Custom) },
            )

            if (state.serverChoice == ServerChoice.Custom) {
                OutlinedTextField(
                    value = state.customUrl,
                    onValueChange = onCustomUrlChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTagCustomUrlField),
                    label = { Text(stringResource(R.string.setup_custom_field_label)) },
                    placeholder = { Text(stringResource(R.string.setup_custom_field_placeholder)) },
                    supportingText = { Text(stringResource(R.string.setup_custom_field_helper)) },
                    singleLine = true,
                )
            }

            Row(
                modifier = Modifier
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
                onClick = onContinue,
                enabled = state.canContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTagContinueButton),
            ) {
                Text(stringResource(R.string.setup_continue))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ServerChoiceRow(
    choice: ServerChoice,
    selected: Boolean,
    titleRes: Int,
    bodyRes: Int,
    detail: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .testTag(testTagFor(choice))
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.padding(start = 8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodySmall,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private fun testTagFor(choice: ServerChoice): String = when (choice) {
    ServerChoice.Default -> TestTagDefaultChoice
    ServerChoice.Custom -> TestTagCustomChoice
}

internal const val TestTagDefaultChoice: String = "setup-default-choice"
internal const val TestTagCustomChoice: String = "setup-custom-choice"
internal const val TestTagCustomUrlField: String = "setup-custom-url-field"
internal const val TestTagConsentRow: String = "setup-consent-row"
internal const val TestTagContinueButton: String = "setup-continue-button"
