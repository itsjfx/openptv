package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.network.PtvSigner
import ac.jfx.openptv.core.network.PtvUrlResolver
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * `PtvUrlResolver` implementation that picks proxy-mode vs direct-mode per call by reading the
 * latest [SettingsRepository] snapshot.
 *
 * - **Proxy mode** (default): returns `${backendBaseUrl}<path>`. The proxy maps `/api/v3/<path>`
 *   to PTV's `/v3/<path>` and signs upstream — the mobile app is unaware.
 * - **Direct mode**: prepends `/v3/`, signs with [PtvSigner] using the user's `devId` + `apiKey`,
 *   and prefixes PTV's host. If the user has flipped direct mode on but left a credential blank,
 *   we fall back to proxy mode for that call so the user's existing search affordance keeps
 *   working — the Settings screen surfaces the credential gap rather than silently breaking the
 *   network. (The settings UI also disables the toggle's "real" effect until both fields are
 *   non-blank, so this is a defence-in-depth fallback rather than the primary UX seam.)
 *
 * Reading via `.first()` per call means a Settings edit takes effect on the next request without
 * touching the Retrofit graph. The cost is one StateFlow snapshot per request; negligible for a
 * search-on-keystroke UX.
 */
internal class SettingsPtvUrlResolver
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) : PtvUrlResolver {
        override suspend fun resolve(path: String): String {
            require(!path.startsWith("/")) { "PtvUrlResolver: path must not start with /, got \"$path\"" }
            val snapshot = settings.settings.first()
            val canSignDirect =
                snapshot.directMode && snapshot.devId.isNotBlank() && snapshot.apiKey.isNotBlank()
            return if (canSignDirect) {
                val signer = PtvSigner(devId = snapshot.devId, key = snapshot.apiKey)
                val signedRelative = signer.sign("/v3/$path")
                "$PTV_HOST$signedRelative"
            } else {
                "${snapshot.backendBaseUrl}$path"
            }
        }

        private companion object {
            // PTV's documented timetable host. Trailing slash intentionally omitted — the
            // signed relative path always starts with `/`.
            const val PTV_HOST: String = "https://timetableapi.ptv.vic.gov.au"
        }
    }
