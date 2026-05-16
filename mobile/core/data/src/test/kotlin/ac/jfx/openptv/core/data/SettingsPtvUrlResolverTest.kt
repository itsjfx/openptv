package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.data.test.FakeSettingsRepository
import ac.jfx.openptv.core.model.AppSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins the contract `SettingsPtvUrlResolver` buys: every call reads the latest settings snapshot,
 * so a Settings-screen edit (URL, direct-mode toggle, devid, key) takes effect on the very next
 * request without app restart or Retrofit rebuild.
 */
class SettingsPtvUrlResolverTest {
    @Test
    fun `proxy mode concats backend base URL with the path`() =
        runTest {
            val settings =
                FakeSettingsRepository().apply {
                    seed(AppSettings(backendBaseUrl = "http://proxy.local/api/v3/", setupCompleted = true))
                }
            val resolver = SettingsPtvUrlResolver(settings)

            assertThat(resolver.resolve("search/flinders"))
                .isEqualTo("http://proxy.local/api/v3/search/flinders")
        }

    @Test
    fun `picks up backend URL changes between calls`() =
        runTest {
            val settings =
                FakeSettingsRepository().apply {
                    seed(AppSettings(backendBaseUrl = "http://first.local/api/v3/", setupCompleted = true))
                }
            val resolver = SettingsPtvUrlResolver(settings)

            val before = resolver.resolve("search/x")
            settings.setBackendBaseUrl("http://second.local/api/v3/")
            val after = resolver.resolve("search/x")

            assertThat(before).isEqualTo("http://first.local/api/v3/search/x")
            assertThat(after).isEqualTo("http://second.local/api/v3/search/x")
        }

    @Test
    fun `direct mode prefixes PTV host and signs the path`() =
        runTest {
            // Pinned against `PtvSignerTest` — same fixture, same expected signature so a future
            // change to the signing scheme breaks both tests at once.
            val settings =
                FakeSettingsRepository().apply {
                    seed(
                        AppSettings(
                            backendBaseUrl = "http://proxy.local/api/v3/",
                            setupCompleted = true,
                            directMode = true,
                            devId = "3000176",
                            apiKey = "9c132d31-6a30-4cac-8d8b-8a1970834799",
                        ),
                    )
                }
            val resolver = SettingsPtvUrlResolver(settings)

            val resolved = resolver.resolve("route_types")

            assertThat(resolved)
                .isEqualTo(
                    "https://timetableapi.ptv.vic.gov.au/v3/route_types?devid=3000176&signature=EBD12B055DFEBB7CC0F9FB2B6E3AA0FE3CFD87B6",
                )
        }

    @Test
    fun `direct mode appends devid with ampersand when path already has a query`() =
        runTest {
            val settings =
                FakeSettingsRepository().apply {
                    seed(
                        AppSettings(
                            backendBaseUrl = "http://proxy.local/api/v3/",
                            setupCompleted = true,
                            directMode = true,
                            devId = "DEV",
                            apiKey = "KEY",
                        ),
                    )
                }
            val resolver = SettingsPtvUrlResolver(settings)

            val resolved = resolver.resolve("stops/1071/route_type/0?stop_location=true")

            assertThat(resolved).startsWith(
                "https://timetableapi.ptv.vic.gov.au/v3/stops/1071/route_type/0" +
                    "?stop_location=true&devid=DEV&signature=",
            )
        }

    @Test
    fun `direct mode falls back to proxy when devid is blank`() =
        runTest {
            val settings =
                FakeSettingsRepository().apply {
                    seed(
                        AppSettings(
                            backendBaseUrl = "http://proxy.local/api/v3/",
                            setupCompleted = true,
                            directMode = true,
                            devId = "",
                            apiKey = "KEY",
                        ),
                    )
                }
            val resolver = SettingsPtvUrlResolver(settings)

            assertThat(resolver.resolve("search/x"))
                .isEqualTo("http://proxy.local/api/v3/search/x")
        }

    @Test
    fun `direct mode falls back to proxy when api key is blank`() =
        runTest {
            val settings =
                FakeSettingsRepository().apply {
                    seed(
                        AppSettings(
                            backendBaseUrl = "http://proxy.local/api/v3/",
                            setupCompleted = true,
                            directMode = true,
                            devId = "DEV",
                            apiKey = "",
                        ),
                    )
                }
            val resolver = SettingsPtvUrlResolver(settings)

            assertThat(resolver.resolve("search/x"))
                .isEqualTo("http://proxy.local/api/v3/search/x")
        }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects path that begins with a slash`() =
        runTest {
            val resolver = SettingsPtvUrlResolver(FakeSettingsRepository())
            resolver.resolve("/v3/search/x")
        }
}
