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

/**
 * Object Mother for the internal [StopDto]. Lives in `:core:network/src/test/` rather than
 * `:core:testing` because [StopDto] is `internal` — only same-module code can construct it.
 * If a different module needed a DTO fixture we'd promote both DTO and mother together; for now
 * the mapper test is the only consumer.
 *
 * See `~/.claude/skills/object-mother/skill.md` for the pattern spec.
 */
internal class StopDtoMother private constructor() {

    companion object {
        private const val DEFAULT_ID = 1071
        private const val DEFAULT_NAME = "Flinders Street Railway Station"
        private const val DEFAULT_SUBURB = "Melbourne City"
        private const val DEFAULT_ROUTE_TYPE = 0
        private const val DEFAULT_LATITUDE = -37.8183
        private const val DEFAULT_LONGITUDE = 144.9671

        internal fun aStopDto(): StopDtoBuilder = StopDtoBuilder()
    }

    internal class StopDtoBuilder {
        private var stopId: Int = DEFAULT_ID
        private var stopName: String = DEFAULT_NAME
        private var stopSuburb: String = DEFAULT_SUBURB
        private var routeType: Int = DEFAULT_ROUTE_TYPE
        private var stopLatitude: Double = DEFAULT_LATITUDE
        private var stopLongitude: Double = DEFAULT_LONGITUDE

        fun withStopId(id: Int) = apply { this.stopId = id }
        fun withStopName(name: String) = apply { this.stopName = name }
        fun withStopSuburb(suburb: String) = apply { this.stopSuburb = suburb }
        fun withRouteType(code: Int) = apply { this.routeType = code }
        fun withStopLatitude(latitude: Double) = apply { this.stopLatitude = latitude }
        fun withStopLongitude(longitude: Double) = apply { this.stopLongitude = longitude }

        fun build(): StopDto = StopDto(
            stopId = stopId,
            stopName = stopName,
            stopSuburb = stopSuburb,
            routeType = routeType,
            stopLatitude = stopLatitude,
            stopLongitude = stopLongitude,
        )
    }
}
