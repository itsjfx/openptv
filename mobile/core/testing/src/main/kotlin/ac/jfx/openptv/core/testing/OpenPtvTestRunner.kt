package ac.jfx.openptv.core.testing

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner that swaps `OpenPtvApplication` (annotated with `@HiltAndroidApp`)
 * for Hilt's [HiltTestApplication] during instrumented tests. Without this runner, the
 * production `@HiltAndroidApp` component would be created at process start and Hilt's
 * `@HiltAndroidTest` machinery would refuse to install fakes.
 *
 * Wired into `:app/build.gradle.kts` via
 * `testInstrumentationRunner = "ac.jfx.openptv.core.testing.OpenPtvTestRunner"`. Every
 * `:feature:*` module that adds instrumented tests inherits the same runner from `:app`'s
 * Gradle config (AGP propagates `testInstrumentationRunner` to library modules' tests).
 *
 * NIA reference: `nowinandroid/core/testing/src/main/kotlin/.../NiaTestRunner.kt`.
 */
class OpenPtvTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, name: String, context: Context): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
