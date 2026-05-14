package ac.jfx.openptv.feature.nearby

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Hilt graph for the maps stack. Owns:
 *
 *  1. A dedicated [OkHttpClient] for MapLibre's tile/style fetches, qualified [MapsHttp], backed
 *     by a 50 MiB [Cache] under `cacheDir/maps/`. Scoped here so the regular `:core:network`
 *     client is untouched — API calls don't pollute the maps cache and vice versa.
 *  2. A [OpenPtvMapInitialiser] that wires the qualified client into MapLibre's HTTP stack
 *     once per process (MapLibre is initialised lazily; we trigger it from
 *     `OpenPtvApplication` so the tile cache is in place before the first MapView appears).
 *
 * The [OpenPtvMap] binding lives here too — production binds it to [MapLibreOpenPtvMap]; tests
 * `@TestInstallIn(replaces = [MapsModule::class])` a fake.
 *
 * Per the issue spec: scope cache to the maps client only, not the regular API client.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object MapsModule {
    @Provides
    @Singleton
    @MapsHttp
    fun provideMapsHttpClient(
        @ApplicationContext context: Context,
    ): OkHttpClient {
        val cacheDir = File(context.cacheDir, "maps").apply { mkdirs() }
        val cache = Cache(cacheDir, MAPS_CACHE_SIZE_BYTES)
        return OkHttpClient.Builder()
            .cache(cache)
            .build()
    }

    /**
     * Triggers MapLibre's first-time init AND hands it our cached OkHttpClient. Constructed as
     * a singleton so the wiring happens exactly once; consumers inject this from
     * [ac.jfx.openptv.feature.nearby.NearbyRoute] before the first MapView appears.
     */
    @Provides
    @Singleton
    fun provideMapInitialiser(
        @ApplicationContext context: Context,
        @MapsHttp httpClient: OkHttpClient,
    ): OpenPtvMapInitialiser = OpenPtvMapInitialiser(context, httpClient)

    /** 50 MiB cache, per the issue spec. */
    private const val MAPS_CACHE_SIZE_BYTES: Long = 50L * 1024L * 1024L
}

/**
 * Bindings split out into an `abstract class` because Hilt requires `@Binds` to live in an
 * abstract class, separate from `@Provides`-hosting `object`s.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MapsBindings {
    @Binds
    @Singleton
    internal abstract fun bindOpenPtvMap(impl: MapLibreOpenPtvMap): OpenPtvMap
}

/**
 * Qualifier for the maps-scoped `OkHttpClient`. Picking a qualifier rather than a typealias means
 * Hilt's binding errors are loud — a forgotten `@MapsHttp` on a consumer surfaces as a missing
 * binding instead of accidentally using the wrong client.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class MapsHttp

/**
 * Single-use MapLibre initialiser. `init()` is idempotent — MapLibre's own
 * `MapLibre.getInstance(...)` short-circuits on a second call, so re-invoking from a process
 * resurrected via Hilt is safe.
 *
 * Public so [ac.jfx.openptv.OpenPtvApplication] can call this from `onCreate` (the cleanest place
 * — the OkHttp client must be on MapLibre's HTTP stack before any tile fetch fires). Constructor
 * is internal because Hilt is the only intended factory.
 */
class OpenPtvMapInitialiser internal constructor(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    /** Idempotent — safe to call from `Application.onCreate` AND from the screen's route. */
    fun init() {
        // `MapLibre.getInstance(context)` lazily initialises the global SDK singleton. Called
        // before `HttpRequestUtil.setOkHttpClient` because the latter writes into the SDK's
        // internal HTTP module; the SDK has to exist first.
        MapLibre.getInstance(context)
        HttpRequestUtil.setOkHttpClient(httpClient)
    }
}
