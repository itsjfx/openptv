package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType

/**
 * Inverse of `RouteType.fromCode`: maps a domain [RouteType] back to the PTV wire code so the
 * data sources can compose `/route_type/{n}` URLs without leaking the integer through the
 * domain model.
 *
 * Lives in `:core:network` because the wire code is a network-layer concern; the domain stays
 * symbolic. `Unknown` is forced to `0` (Train) as a defensive default — if a caller ever
 * constructs a request with `Unknown`, treating it as Train is less surprising than a 4xx and
 * means the rest of the screen can still render. The UI shouldn't be passing `Unknown` here in
 * the first place.
 */
@Suppress("MagicNumber")
internal fun RouteType.toPtvCode(): Int =
    when (this) {
        RouteType.Train -> 0
        RouteType.Tram -> 1
        RouteType.Bus -> 2
        RouteType.VLine -> 3
        RouteType.NightBus -> 4
        RouteType.Unknown -> 0
    }
