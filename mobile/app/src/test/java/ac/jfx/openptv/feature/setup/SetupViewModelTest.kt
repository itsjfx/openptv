package ac.jfx.openptv.feature.setup

import ac.jfx.openptv.core.data.test.FakeSettingsRepository
import ac.jfx.openptv.core.model.AppSettings
import ac.jfx.openptv.core.testing.util.MainDispatcherRule
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
                // Initial empty defaultUrl is replaced by the persisted value once the init job runs.
                val seeded = expectMostRecentItem()
                assertThat(seeded.defaultUrl).isEqualTo("http://default.local/api/v3/")
                assertThat(seeded.serverChoice).isEqualTo(ServerChoice.Default)
                assertThat(seeded.canContinue).isFalse()
            }
        }

    @Test
    fun `canContinue requires consent`() =
        runTest {
            val viewModel = SetupViewModel(settings)
            viewModel.onConsentToggled(true)
            assertThat(viewModel.uiState.value.canContinue).isTrue()
            viewModel.onConsentToggled(false)
            assertThat(viewModel.uiState.value.canContinue).isFalse()
        }

    @Test
    fun `custom server choice without URL cannot continue`() =
        runTest {
            val viewModel = SetupViewModel(settings)
            viewModel.onServerChoiceChanged(ServerChoice.Custom)
            viewModel.onConsentToggled(true)
            assertThat(viewModel.uiState.value.canContinue).isFalse()
            viewModel.onCustomUrlChanged("http://10.0.2.2:8080/api/v3/")
            assertThat(viewModel.uiState.value.canContinue).isTrue()
        }

    @Test
    fun `completeSetup persists chosen default URL and flips flag`() =
        runTest {
            val viewModel = SetupViewModel(settings)
            viewModel.onConsentToggled(true)
            var doneCalled = false
            viewModel.completeSetup { doneCalled = true }

            val stored = settings.settings.first()
            assertThat(stored.backendBaseUrl).isEqualTo("http://default.local/api/v3/")
            assertThat(stored.setupCompleted).isTrue()
            assertThat(doneCalled).isTrue()
        }

    @Test
    fun `completeSetup persists custom URL`() =
        runTest {
            val viewModel = SetupViewModel(settings)
            viewModel.onServerChoiceChanged(ServerChoice.Custom)
            viewModel.onCustomUrlChanged("http://192.168.1.5:8080/api/v3/")
            viewModel.onConsentToggled(true)
            viewModel.completeSetup { }

            assertThat(settings.settings.first().backendBaseUrl)
                .isEqualTo("http://192.168.1.5:8080/api/v3/")
        }

    @Test
    fun `completeSetup is a no-op without consent`() =
        runTest {
            val viewModel = SetupViewModel(settings)
            var doneCalled = false
            viewModel.completeSetup { doneCalled = true }
            assertThat(doneCalled).isFalse()
            assertThat(settings.settings.first().setupCompleted).isFalse()
        }
}
