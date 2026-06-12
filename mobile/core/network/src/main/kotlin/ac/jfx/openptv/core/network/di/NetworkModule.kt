package ac.jfx.openptv.core.network.di

import ac.jfx.openptv.core.network.BackendApiService
import ac.jfx.openptv.core.network.BuildConfig
import ac.jfx.openptv.core.network.DepartureDataSource
import ac.jfx.openptv.core.network.NearbyStopsDataSource
import ac.jfx.openptv.core.network.RetrofitDepartureDataSource
import ac.jfx.openptv.core.network.RetrofitNearbyStopsDataSource
import ac.jfx.openptv.core.network.RetrofitRunPatternDataSource
import ac.jfx.openptv.core.network.RetrofitStopDetailDataSource
import ac.jfx.openptv.core.network.RetrofitStopSearchDataSource
import ac.jfx.openptv.core.network.RunPatternDataSource
import ac.jfx.openptv.core.network.StopDetailDataSource
import ac.jfx.openptv.core.network.StopSearchDataSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Networking graph. One shared [OkHttpClient] (so the connection / thread pools are pooled),
 * one [Retrofit], one [BackendApiService]. Timeouts (15 s connect / 30 s read) come from the
 * phase 02 spec.
 *
 * The HTTP logging interceptor only logs request/response bodies in debug builds — release
 * gets `NONE` so PII never leaks into logcat on a user device. `BuildConfig.DEBUG` resolves to
 * this module's own BuildConfig (one per Gradle module on AGP 9) so the toggle still tracks
 * the actual build type, not just whether the host app is debug.
 *
 * The Retrofit base URL is a fixed sentinel string: every endpoint in [BackendApiService] uses
 * `@Url` with the absolute URL composed by the repository from the user's configured backend.
 * Retrofit requires `baseUrl` at build time even when nothing uses it, so we pass a no-op value.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging =
            HttpLoggingInterceptor().apply {
                level =
                    if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BODY
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
            }
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        json: Json,
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(RETROFIT_BASE_URL_SENTINEL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    internal fun provideBackendApiService(retrofit: Retrofit): BackendApiService =
        retrofit.create(BackendApiService::class.java)

    // Retrofit demands a non-empty `baseUrl` at build time even if every endpoint uses
    // absolute `@Url` strings. The host is intentionally unreachable so a forgotten `@Url`
    // would fail loudly in tests instead of silently calling the wrong place.
    private const val RETROFIT_BASE_URL_SENTINEL: String = "http://localhost.invalid/"
    private const val CONNECT_TIMEOUT_SECONDS: Long = 15
    private const val READ_TIMEOUT_SECONDS: Long = 30
}

/**
 * Binds [StopSearchDataSource] to its Retrofit-backed impl. Separate `@Module abstract class`
 * because `@Binds` and `@Provides` can't coexist in the same `object` module — Hilt needs the
 * `@Binds` host to be an abstract class.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkBindings {
    @Binds
    @Singleton
    internal abstract fun bindStopSearchDataSource(
        impl: RetrofitStopSearchDataSource,
    ): StopSearchDataSource

    @Binds
    @Singleton
    internal abstract fun bindStopDetailDataSource(
        impl: RetrofitStopDetailDataSource,
    ): StopDetailDataSource

    @Binds
    @Singleton
    internal abstract fun bindDepartureDataSource(
        impl: RetrofitDepartureDataSource,
    ): DepartureDataSource

    @Binds
    @Singleton
    internal abstract fun bindNearbyStopsDataSource(
        impl: RetrofitNearbyStopsDataSource,
    ): NearbyStopsDataSource

    @Binds
    @Singleton
    internal abstract fun bindRunPatternDataSource(
        impl: RetrofitRunPatternDataSource,
    ): RunPatternDataSource
}
