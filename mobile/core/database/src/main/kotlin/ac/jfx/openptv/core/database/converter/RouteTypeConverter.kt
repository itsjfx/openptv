package ac.jfx.openptv.core.database.converter

import ac.jfx.openptv.core.model.RouteType
import androidx.room.TypeConverter

/**
 * Persists [RouteType] as its enum `name()` (e.g. `"Tram"`) — a stable, human-readable string
 * that doesn't depend on the PTV wire code. The wire-code mapping lives in `:core:network`
 * (`RouteTypeWire.toPtvCode`) and is intentionally not used here: if PTV ever renumbers route
 * types, a string-name column survives the change without a migration. Unknown wire values
 * already collapse to [RouteType.Unknown] before they reach the DB, so the round-trip stays
 * total.
 *
 * Stored as `name()` rather than ordinal so reordering or inserting into the enum doesn't
 * silently corrupt rows.
 */
internal class RouteTypeConverter {
    @TypeConverter
    fun fromRouteType(value: RouteType): String = value.name

    @TypeConverter
    fun toRouteType(value: String): RouteType =
        runCatching { RouteType.valueOf(value) }.getOrDefault(RouteType.Unknown)
}
