package ac.jfx.openptv

import ac.jfx.openptv.feature.nearby.OpenPtvMapInitialiser
import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. `@HiltAndroidApp` triggers Hilt's compile-time code generation,
 * making this the root of the dependency graph (analogous to `@SpringBootApplication`).
 *
 * **MapLibre eager init.** MapView's constructor throws `MapLibreConfigurationException` if
 * `MapLibre.getInstance(context)` hasn't been called yet — and the call has to happen on the
 * main thread, before any `MapView(context)` ctor fires. Doing it from a `LaunchedEffect` inside
 * the feature route races the AndroidView factory; doing it here from `onCreate` is the
 * canonical fix (matches MapLibre's own README examples). The injection happens at Application
 * scope so the same OkHttpClient (with the 50 MiB cache) is wired into MapLibre's HTTP stack.
 */
@HiltAndroidApp
class OpenPtvApplication : Application() {
    @Inject
    lateinit var mapInitialiser: OpenPtvMapInitialiser

    override fun onCreate() {
        super.onCreate()
        mapInitialiser.init()
    }
}
