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

import ac.jfx.openptv.core.model.RouteType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure mapper test: real DTOs, real mapper, no doubles. Covers every documented route_type code
 * plus an unknown value that has to fall back to [RouteType.Unknown]. Also pins the trailing-
 * whitespace cleanup behaviour because PTV emits `"20 Matthew Flinders Ave "` in real responses.
 */
class SearchDtoMapperTest {

    @Test
    fun `maps every documented route_type code`() {
        val pairs = listOf(
            0 to RouteType.Train,
            1 to RouteType.Tram,
            2 to RouteType.Bus,
            3 to RouteType.VLine,
            4 to RouteType.NightBus,
        )
        pairs.forEach { (code, expected) ->
            assertThat(RouteType.fromCode(code)).isEqualTo(expected)
        }
    }

    @Test
    fun `unknown route_type falls back to Unknown`() {
        assertThat(RouteType.fromCode(99)).isEqualTo(RouteType.Unknown)
    }

    @Test
    fun `StopDto trims trailing whitespace on name and suburb`() {
        val dto = StopDtoMother.aStopDto()
            .withStopName("Flinders Street Railway Station ")
            .withStopSuburb("Melbourne City  ")
            .build()

        val stop = dto.toDomain()

        assertThat(stop.name).isEqualTo("Flinders Street Railway Station")
        assertThat(stop.suburb).isEqualTo("Melbourne City")
        assertThat(stop.routeType).isEqualTo(RouteType.Train)
        assertThat(stop.id.value).isEqualTo(1071)
    }

    @Test
    fun `SearchResponseDto with no stops maps to empty list`() {
        val empty = SearchResponseDto(stops = emptyList())
        assertThat(empty.toDomain()).isEmpty()
    }

    @Test
    fun `mapper preserves entries that share a stop_id but differ in route_type`() {
        val response = SearchResponseDto(
            stops = listOf(
                StopDtoMother.aStopDto().withRouteType(0).build(),
                StopDtoMother.aStopDto().withRouteType(1).build(),
            ),
        )

        val stops = response.toDomain()

        assertThat(stops).hasSize(2)
        assertThat(stops.map { it.id.value }).containsExactly(1071, 1071).inOrder()
        assertThat(stops.map { it.routeType })
            .containsExactly(RouteType.Train, RouteType.Tram).inOrder()
    }
}
