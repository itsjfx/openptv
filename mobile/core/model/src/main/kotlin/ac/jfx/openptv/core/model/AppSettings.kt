package ac.jfx.openptv.core.model

/**
 * Persisted app settings. Domain type with no Android deps — promoted to `:core:model`
 * alongside the multi-module split.
 *
 * - [backendBaseUrl] is the user-chosen proxy URL. It MUST end with a trailing slash because
 *   Retrofit's `@Url` resolves relative paths against it (`baseUrl + "search/$term"`).
 * - [setupCompleted] gates the network layer: until the user has explicitly chosen a server
 *   and accepted, no request leaves the device.
 * - [directMode] flips the network layer from "talk to the proxy at [backendBaseUrl]" to
 *   "sign requests with [devId] + [apiKey] and call PTV directly". Defaults to `false` —
 *   existing installs keep using the proxy until the user opts in. The proxy URL row stays
 *   visible while direct mode is on so users can flip back without re-typing the URL.
 * - [devId] / [apiKey] are the PTV-issued credentials used for HMAC-SHA1 signing in direct
 *   mode. Stored as plain text in DataStore for now; encrypted storage is out of scope. The
 *   API key never leaks into composition locals — only the ViewModel reads it.
 */
data class AppSettings(
    val backendBaseUrl: String,
    val setupCompleted: Boolean,
    val directMode: Boolean = false,
    val devId: String = "",
    val apiKey: String = "",
)
