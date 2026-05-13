package ac.jfx.openptv.feature.settings

import ac.jfx.openptv.core.data.test.FakeSettingsRepository
import ac.jfx.openptv.core.model.AppSettings
import ac.jfx.openptv.core.testing.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings =
        FakeSettingsRepository().apply {
            seed(
                AppSettings(
                    backendBaseUrl = "http://saved.local/api/v3/",
                    setupCompleted = true,
                ),
            )
        }

    @Test
    fun `loads saved URL into draft`() =
        runTest {
            val viewModel = SettingsViewModel(settings)
            val state = viewModel.uiState.value
            assertThat(state.savedUrl).isEqualTo("http://saved.local/api/v3/")
            assertThat(state.draftUrl).isEqualTo("http://saved.local/api/v3/")
            assertThat(state.dirty).isFalse()
            assertThat(state.loaded).isTrue()
        }

    @Test
    fun `editing draft marks state dirty`() =
        runTest {
            val viewModel = SettingsViewModel(settings)
            viewModel.onDraftUrlChanged("http://new.local/api/v3/")
            assertThat(viewModel.uiState.value.dirty).isTrue()
        }

    @Test
    fun `save persists draft and clears dirty`() =
        runTest {
            val viewModel = SettingsViewModel(settings)
            viewModel.onDraftUrlChanged("http://new.local/api/v3/")
            viewModel.onSave()

            // The normalisation in the repository keeps the trailing slash.
            assertThat(settings.settings.first().backendBaseUrl)
                .isEqualTo("http://new.local/api/v3/")
            assertThat(viewModel.uiState.value.dirty).isFalse()
        }

    @Test
    fun `save is a no-op when draft is blank`() =
        runTest {
            val viewModel = SettingsViewModel(settings)
            viewModel.onDraftUrlChanged("")
            viewModel.onSave()
            // The original value remains; the empty draft isn't pushed.
            assertThat(settings.settings.first().backendBaseUrl)
                .isEqualTo("http://saved.local/api/v3/")
        }
}
