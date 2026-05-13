package ac.jfx.openptv.core.model

/**
 * Persisted app settings. Domain type with no Android deps — promoted to `:core:model`
 * alongside the multi-module split.
 *
 * - [backendBaseUrl] is the user-chosen proxy URL. It MUST end with a trailing slash because
 *   Retrofit's `@Url` resolves relative paths against it (`baseUrl + "search/$term"`).
 * - [setupCompleted] gates the network layer: until the user has explicitly chosen a server
 *   and accepted, no request leaves the device.
 */
data class AppSettings(
    val backendBaseUrl: String,
    val setupCompleted: Boolean,
)
