package ac.jfx.openptv.feature.setup

import ac.jfx.openptv.core.data.test.FakeSettingsRepository
import ac.jfx.openptv.core.model.AppSettings
import ac.jfx.openptv.core.testing.util.MainDispatcherRule
import ac.jfx.openptv.feature.settings.ServerChoice
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SetupViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings =
        FakeSettingsRepository().apply {
            seed(
                AppSettings(
                    backendBaseUrl = "http://default.local/api/v3/",
                    setupCompleted = false,
                ),
            )
        }

    @Test
    fun `seeds default URL from settings`() =
        runTest {
            val viewModel = SetupViewModel(settings)
            viewModel.uiState.test {
                val seeded = expectMostRecentItem()
                assertThat(seeded.pickerState.defaultUrl).isEqualTo("http://default.local/api/v3/")
                assertThat(seeded.pickerState.choice).isEqualTo(ServerChoice.Default)
                assertThat(seeded.canContinue).isTrue()
            }
        }

    @Test
    fun `custom server choice without URL cannot continue`() =
        runTest {
            val viewModel = SetupViewModel(settings)
            viewModel.onPickerStateChanged(
                viewModel.uiState.value.pickerState.copy(choice = ServerChoice.Custom),
            )
            assertThat(viewModel.uiState.value.canContinue).isFalse()
            viewModel.onPickerStateChanged(
                viewModel.uiState.value.pickerState.copy(customUrl = "http://10.0.2.2:8080/api/v3/"),
            )
            assertThat(viewModel.uiState.value.canContinue).isTrue()
        }

    @Test
    fun `direct PTV choice without credentials cannot continue`() =
        runTest {
            val viewModel = SetupViewModel(settings)
            viewModel.onPickerStateChanged(
                viewModel.uiState.value.pickerState.copy(choice = ServerChoice.DirectPtv),
            )
            assertThat(viewModel.uiState.value.canContinue).isFalse()
            viewModel.onPickerStateChanged(
                viewModel.uiState.value.pickerState.copy(devId = "3000176"),
            )
            assertThat(viewModel.uiState.value.canContinue).isFalse()
            viewModel.onPickerStateChanged(
                viewModel.uiState.value.pickerState.copy(
                    apiKey = "9c132d31-6a30-4cac-8d8b-8a1970834799",
                ),
            )
            assertThat(viewModel.uiState.value.canContinue).isTrue()
        }

    @Test
    fun `completeSetup persists chosen default URL and flips flag`() =
        runTest {
            val viewModel = SetupViewModel(settings)
            var doneCalled = false
            viewModel.completeSetup { doneCalled = true }

            val stored = settings.settings.first()
            assertThat(stored.backendBaseUrl).isEqualTo("http://default.local/api/v3/")
            assertThat(stored.directMode).isFalse()
            assertThat(stored.setupCompleted).isTrue()
            assertThat(doneCalled).isTrue()
        }

    @Test
    fun `completeSetup persists custom URL`() =
        runTest {
            val viewModel = SetupViewModel(settings)
            viewModel.onPickerStateChanged(
                viewModel.uiState.value.pickerState.copy(
                    choice = ServerChoice.Custom,
                    customUrl = "http://192.168.1.5:8080/api/v3/",
                ),
            )
            viewModel.completeSetup { }

            val stored = settings.settings.first()
            assertThat(stored.backendBaseUrl).isEqualTo("http://192.168.1.5:8080/api/v3/")
            assertThat(stored.directMode).isFalse()
        }

    @Test
    fun `completeSetup direct PTV persists credentials and flips direct mode on`() =
        runTest {
            val viewModel = SetupViewModel(settings)
            viewModel.onPickerStateChanged(
                viewModel.uiState.value.pickerState.copy(
                    choice = ServerChoice.DirectPtv,
                    devId = "3000176",
                    apiKey = "9c132d31-6a30-4cac-8d8b-8a1970834799",
                ),
            )
            viewModel.completeSetup { }

            val stored = settings.settings.first()
            assertThat(stored.directMode).isTrue()
            assertThat(stored.devId).isEqualTo("3000176")
            assertThat(stored.apiKey).isEqualTo("9c132d31-6a30-4cac-8d8b-8a1970834799")
            assertThat(stored.backendBaseUrl).isEqualTo("http://default.local/api/v3/")
            assertThat(stored.setupCompleted).isTrue()
        }

    @Test
    fun `completeSetup is a no-op when picker state is not committable`() =
        runTest {
            val viewModel = SetupViewModel(settings)
            viewModel.onPickerStateChanged(
                viewModel.uiState.value.pickerState.copy(choice = ServerChoice.Custom, customUrl = ""),
            )
            var doneCalled = false
            viewModel.completeSetup { doneCalled = true }
            assertThat(doneCalled).isFalse()
            assertThat(settings.settings.first().setupCompleted).isFalse()
        }
}
