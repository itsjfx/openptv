/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Exercises the Retrofit-bound [BackendApiService] against [MockWebServer]. No MockK: the goal is
 * to assert the real wire contract — JSON shape, HTTP status mapping, request path — not to mock
 * a fake collaborator.
 *
 * The service now takes a full URL via `@Url` so the test composes one against the mock server,
 * mirroring how `StopSearchRepositoryImpl` does it in production.
 */
class BackendApiServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: BackendApiService
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/api/v3/").toString()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/sentinel/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        service = retrofit.create(BackendApiService::class.java)
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `success response parses into SearchResponseDto`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"stops":[{"stop_id":1071,"stop_name":"Flinders Street Railway Station","stop_suburb":"Melbourne City","route_type":0,"stop_latitude":-37.8183,"stop_longitude":144.9671}],"routes":[],"outlets":[]}
                    """.trimIndent(),
                ),
        )

        val response = service.searchStops("${baseUrl}search/flinders")

        assertThat(response.stops).hasSize(1)
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/api/v3/search/flinders")
    }

    @Test(expected = HttpException::class)
    fun `4xx surfaces as HttpException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        service.searchStops("${baseUrl}search/does-not-exist")
    }

    @Test(expected = HttpException::class)
    fun `5xx surfaces as HttpException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream down"))
        service.searchStops("${baseUrl}search/anything")
    }

    @Test(expected = SerializationException::class)
    fun `malformed JSON surfaces as SerializationException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{not json"))
        service.searchStops("${baseUrl}search/anything")
    }
}
