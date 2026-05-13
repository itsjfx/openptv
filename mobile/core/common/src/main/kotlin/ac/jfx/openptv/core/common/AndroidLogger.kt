package ac.jfx.openptv.core.common

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [Logger] backed by `android.util.Log`. This is the ONLY class in the codebase
 * allowed to import `android.util.Log`; everywhere else routes through the [Logger] interface.
 * The detekt rule wired in #13 will fail the build if that boundary is broken.
 *
 * `Log.*` returns the number of bytes written, which we deliberately drop — callers care about
 * the side effect (the log line), not the platform's internal accounting.
 */
@Singleton
class AndroidLogger @Inject constructor() : Logger {
    override fun v(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.v(tag, message, throwable) else Log.v(tag, message)
    }

    override fun d(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.d(tag, message, throwable) else Log.d(tag, message)
    }

    override fun i(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.i(tag, message, throwable) else Log.i(tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}
