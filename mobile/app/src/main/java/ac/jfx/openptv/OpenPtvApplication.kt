package ac.jfx.openptv

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. `@HiltAndroidApp` triggers Hilt's compile-time code generation,
 * making this the root of the dependency graph (analogous to `@SpringBootApplication`).
 */
@HiltAndroidApp
class OpenPtvApplication : Application()
