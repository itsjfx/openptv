package ac.jfx.openptv.core.network

import ac.jfx.openptv.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Networking graph. One shared [OkHttpClient] (so the connection / thread pools are pooled),
 * one [Retrofit], one [BackendApiService]. Timeouts (15 s connect / 30 s read) come from the
 * phase 02 spec.
 *
 * The HTTP logging interceptor only logs request/response bodies in debug builds — release
 * gets `NONE` so PII never leaks into logcat on a user device.
 *
 * The Retrofit base URL is a build-time sentinel; the real per-call URL comes from the user's
 * stored [ac.jfx.openptv.core.data.SettingsRepository] value and is supplied via `@Url` in
 * [BackendApiService]. Retrofit still requires a non-empty `baseUrl` at build time even if
 * every endpoint uses absolute URLs, so the BuildConfig value doubles as that placeholder.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
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
    ): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BACKEND_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    internal fun provideBackendApiService(retrofit: Retrofit): BackendApiService =
        retrofit.create(BackendApiService::class.java)

    private const val CONNECT_TIMEOUT_SECONDS: Long = 15
    private const val READ_TIMEOUT_SECONDS: Long = 30
}
