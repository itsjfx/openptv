package ac.jfx.openptv.core.common

/**
 * Project-wide logging seam. Production code MUST go through this interface rather than calling
 * `android.util.Log` directly — `:core:common` is the only module allowed to import that class
 * (a detekt rule in #13 will enforce the boundary).
 *
 * Why an interface, not a static facade:
 *
 * - **Testability.** Hilt swaps in a no-op or capturing fake for unit tests; static `Log.*` calls
 *   would have to be silenced via Robolectric or Powermock.
 * - **Future targets.** A Roborazzi / JVM unit-test variant of the app needs something that
 *   doesn't crash without an Android runtime; an interface lets each variant bind whatever it
 *   wants.
 *
 * No Timber, deliberately — NIA also avoids Timber. The standard `Log` mapping is enough for
 * this project and one dep removed is one dep less to keep on a release-mode allowlist.
 */
interface Logger {
    fun v(tag: String, message: String, throwable: Throwable? = null)
    fun d(tag: String, message: String, throwable: Throwable? = null)
    fun i(tag: String, message: String, throwable: Throwable? = null)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
