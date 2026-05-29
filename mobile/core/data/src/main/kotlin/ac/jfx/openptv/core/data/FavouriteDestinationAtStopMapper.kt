package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.database.entity.FavouriteDestinationAtStopEntity
import ac.jfx.openptv.core.model.FavouriteDestinationAtStop
import ac.jfx.openptv.core.model.StopId
import kotlinx.datetime.Instant

/**
 * Entity ↔ domain mappers for [FavouriteDestinationAtStop]. Extension functions so callers read
 * `entity.toDomain()` / `favourite.toEntity()` at the boundary without an injected mapper class.
 *
 * `addedAt` is the only field that changes shape: the entity stores epoch ms (column stays
 * `INTEGER`, no `TypeConverter`), the domain carries a real [Instant].
 *
 * `position` round-trips through the domain unchanged. The repository regenerates it on insert
 * and on `reorder(…)`, so domain consumers can read it but should not mutate it.
 */
internal fun FavouriteDestinationAtStopEntity.toDomain(): FavouriteDestinationAtStop =
    FavouriteDestinationAtStop(
        stopId = StopId(stopId),
        destinationKey = destinationKey,
        routeType = routeType,
        stopName = stopName,
        stopSuburb = stopSuburb,
        destinationName = destinationName,
        lat = lat,
        lng = lng,
        position = position,
        addedAt = Instant.fromEpochMilliseconds(addedAt),
    )

internal fun FavouriteDestinationAtStop.toEntity(): FavouriteDestinationAtStopEntity =
    FavouriteDestinationAtStopEntity(
        stopId = stopId.value,
        destinationKey = destinationKey,
        routeType = routeType,
        stopName = stopName,
        stopSuburb = stopSuburb,
        destinationName = destinationName,
        lat = lat,
        lng = lng,
        position = position,
        addedAt = addedAt.toEpochMilliseconds(),
    )
