package ac.jfx.openptv.core.model

/**
 * Domain types for stops. Pure data classes with no Android dependencies — promoted to
 * `:core:model` in the multi-module follow-up.
 */

@JvmInline
value class StopId(val value: Int)

/**
 * PTV transport modes. `route_type` integer codes come from the PTV API:
 *
 *   0 = Train, 1 = Tram, 2 = Bus, 3 = V/Line (regional train + coach), 4 = Night Bus.
 *
 * `Unknown` exists so an unexpected upstream value never crashes a UI list — the screen can still
 * render a stop, just without a mode icon.
 */
enum class RouteType {
    Train,
    Tram,
    Bus,
    VLine,
    NightBus,
    Unknown,
    ;

    companion object {
        // Wire codes are defined by the PTV Timetable API contract; mapping them in-place reads
        // more naturally than a parallel constants table.
        @Suppress("MagicNumber")
        fun fromCode(code: Int): RouteType =
            when (code) {
                0 -> Train
                1 -> Tram
                2 -> Bus
                3 -> VLine
                4 -> NightBus
                else -> Unknown
            }
    }

    /**
     * Inverse of [fromCode]. [Unknown] maps to `-1` (never round-trips back to itself) — feature
     * code should never put an [Unknown] on the wire; the type is the runtime fall-back, not a
     * normal value. Navigation uses this to serialise the route-type into the destination key.
     */
    @Suppress("MagicNumber")
    fun toCode(): Int =
        when (this) {
            Train -> 0
            Tram -> 1
            Bus -> 2
            VLine -> 3
            NightBus -> 4
            Unknown -> -1
        }
}

/**
 * Minimal stop projection — name, suburb, transport mode, geo. Phase 03 (stop detail) will
 * augment this with the served routes; for the search list we only need what the row renders.
 */
data class Stop(
    val id: StopId,
    val name: String,
    val suburb: String,
    val routeType: RouteType,
    val latitude: Double,
    val longitude: Double,
)
