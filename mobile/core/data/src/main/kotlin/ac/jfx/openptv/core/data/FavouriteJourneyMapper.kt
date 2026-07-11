package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.database.entity.FavouriteJourneyEntity
import ac.jfx.openptv.core.model.FavouriteJourney
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopId
import kotlinx.datetime.Instant

/**
 * Entity ↔ domain mappers for [FavouriteJourney] (issue #209). Extension functions at the
 * repository boundary, same shape as `FavouriteDestinationAtStopMapper`. The entity flattens
 * both endpoints into prefixed columns; the domain re-hydrates them as two full [Stop]s so the
 * favourites screen can hand them straight to `JourneyPlannerRepository.getJourneys(...)` and
 * the planner prefill.
 */
internal fun FavouriteJourneyEntity.toDomain(): FavouriteJourney =
    FavouriteJourney(
        origin =
            Stop(
                id = StopId(originStopId),
                name = originStopName,
                suburb = originStopSuburb,
                routeType = originRouteType,
                latitude = originLat,
                longitude = originLng,
            ),
        destination =
            Stop(
                id = StopId(destinationStopId),
                name = destinationStopName,
                suburb = destinationStopSuburb,
                routeType = destinationRouteType,
                latitude = destinationLat,
                longitude = destinationLng,
            ),
        addedAt = Instant.fromEpochMilliseconds(addedAt),
    )

internal fun FavouriteJourney.toEntity(): FavouriteJourneyEntity =
    FavouriteJourneyEntity(
        originStopId = origin.id.value,
        originStopName = origin.name,
        originStopSuburb = origin.suburb,
        originRouteType = origin.routeType,
        originLat = origin.latitude,
        originLng = origin.longitude,
        destinationStopId = destination.id.value,
        destinationStopName = destination.name,
        destinationStopSuburb = destination.suburb,
        destinationRouteType = destination.routeType,
        destinationLat = destination.latitude,
        destinationLng = destination.longitude,
        addedAt = addedAt.toEpochMilliseconds(),
    )
