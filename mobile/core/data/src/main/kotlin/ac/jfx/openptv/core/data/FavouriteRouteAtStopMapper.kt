package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.database.entity.FavouriteRouteAtStopEntity
import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.FavouriteRouteAtStop
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.StopId
import kotlinx.datetime.Instant

/**
 * Entity ↔ domain mappers for [FavouriteRouteAtStop]. Extension functions so callers read
 * `entity.toDomain()` / `favourite.toEntity(position = …)` at the boundary without an injected
 * mapper class — the conversion is total and stateless, no construction needed.
 *
 * `addedAt` is the only field that changes shape: the entity stores epoch ms (so the column
 * stays `INTEGER`, no `TypeConverter` overhead), the domain carries a real [Instant]. The
 * round-trip is `Instant.fromEpochMilliseconds(…)` / `.toEpochMilliseconds()` and is exact for
 * values within the supported range — favourites won't be added during the Big Bang or the heat
 * death of the universe.
 *
 * `position` is allowed to round-trip through the domain unchanged. The repository regenerates
 * it on insert and on `reorder(…)`, so domain consumers can read it but should not mutate it.
 */
internal fun FavouriteRouteAtStopEntity.toDomain(): FavouriteRouteAtStop =
    FavouriteRouteAtStop(
        stopId = StopId(stopId),
        routeType = routeType,
        routeId = RouteId(routeId),
        directionId = DirectionId(directionId),
        stopName = stopName,
        stopSuburb = stopSuburb,
        routeNumber = routeNumber,
        routeName = routeName,
        directionName = directionName,
        lat = lat,
        lng = lng,
        position = position,
        addedAt = Instant.fromEpochMilliseconds(addedAt),
    )

internal fun FavouriteRouteAtStop.toEntity(): FavouriteRouteAtStopEntity =
    FavouriteRouteAtStopEntity(
        stopId = stopId.value,
        routeType = routeType,
        routeId = routeId.value,
        directionId = directionId.value,
        stopName = stopName,
        stopSuburb = stopSuburb,
        routeNumber = routeNumber,
        routeName = routeName,
        directionName = directionName,
        lat = lat,
        lng = lng,
        position = position,
        addedAt = addedAt.toEpochMilliseconds(),
    )
