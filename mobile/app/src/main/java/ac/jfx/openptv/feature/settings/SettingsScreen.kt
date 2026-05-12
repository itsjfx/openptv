package ac.jfx.openptv.feature.settings

import ac.jfx.openptv.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
 * Settings screen — currently just the backend URL editor. Future settings (theme override,
 * units, etc.) land here in later phases.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreenContent(
        state = state,
        onDraftUrlChanged = viewModel::onDraftUrlChanged,
        onSave = viewModel::onSave,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    state: SettingsUiState,
    onDraftUrlChanged: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_backend_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedTextField(
                value = state.draftUrl,
                onValueChange = onDraftUrlChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTagBackendUrlField),
                label = { Text(stringResource(R.string.settings_backend_label)) },
                supportingText = { Text(stringResource(R.string.settings_backend_helper)) },
                singleLine = true,
                enabled = state.loaded,
            )
            Button(
                onClick = onSave,
                enabled = state.dirty && state.draftUrl.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTagSaveButton),
            ) {
                Text(stringResource(R.string.settings_save))
            }
        }
    }
}

internal const val TestTagBackendUrlField: String = "settings-backend-url-field"
internal const val TestTagSaveButton: String = "settings-save-button"
