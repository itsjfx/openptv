package ac.jfx.openptv.core.datastore

import ac.jfx.openptv.core.datastore.preference.PreferenceKeys
import ac.jfx.openptv.core.model.FollowedTrip
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.model.StopId
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for the single followed trip (issue #200), backed by the `@UserPreferences`
 * DataStore under [PreferenceKeys.FOLLOWED_TRIP].
 *
 * Unlike the fire-and-forget `Preference<T>` DSL, writes here are `suspend` — the follow action
 * is a repository-mediated user intent ("I'm on this vehicle"), not a settings toggle, and the
 * caller (`FollowedTripRepositoryImpl` in `:core:data`) wants completion semantics so a follow
 * that raced process death isn't silently dropped.
 *
 * **Wire format (one-way door).** A JSON object under the string key `followed_trip` with
 * snake_case fields (`run_ref`, `route_type` as the PTV int code, `from_stop_id`, `route_label`,
 * `destination_name`, `completes_at_utc` / `followed_at_utc` as ISO-8601 instants). The DTO stays
 * `internal` so the shape can only be evolved here; decoding is lenient — unknown fields are
 * ignored (a newer build may have written extras) and a malformed payload decodes to `null`
 * (nothing followed) rather than crashing the flow.
 *
 * Constructor is public for the same reason as [UserPreferencesDataStore]'s: tests build a
 * temp-file-backed real DataStore and wrap it directly, proving the wire format round-trips
 * without a Hilt graph.
 */
@Singleton
class FollowedTripDataSource
    @Inject
    constructor(
        @UserPreferences private val dataStore: DataStore<Preferences>,
    ) {
        /** The currently followed trip, or `null` when nothing is followed. */
        val followedTrip: Flow<FollowedTrip?> =
            dataStore.data.map { prefs -> decode(prefs[PreferenceKeys.FOLLOWED_TRIP]) }

        /** Persist [trip] as the followed trip, replacing any previous one. */
        suspend fun set(trip: FollowedTrip) {
            dataStore.edit { prefs ->
                prefs[PreferenceKeys.FOLLOWED_TRIP] = json.encodeToString(trip.toStored())
            }
        }

        /** Remove the followed trip (unfollow / trip complete). */
        suspend fun clear() {
            dataStore.edit { prefs -> prefs.remove(PreferenceKeys.FOLLOWED_TRIP) }
        }

        private fun decode(stored: String?): FollowedTrip? {
            if (stored == null) return null
            return try {
                json.decodeFromString<StoredFollowedTrip>(stored).toDomain()
            } catch (_: SerializationException) {
                // Malformed payload (corruption, or an incompatible older build's write):
                // treat as "nothing followed" — losing a follow is strictly better than
                // crashing every collector of the flow.
                null
            } catch (_: IllegalArgumentException) {
                // Structurally valid JSON that violates the DTO contract (e.g. a non-ISO
                // instant string). Same posture as above.
                null
            }
        }

        private companion object {
            /**
             * `ignoreUnknownKeys` for forward-compat: a newer build (e.g. issue #201's alight
             * alert) may persist extra fields this build doesn't know about.
             */
            private val json = Json { ignoreUnknownKeys = true }
        }
    }

/**
 * The on-disk JSON shape. `internal` — never leaks past this module; the domain type is
 * [FollowedTrip]. `route_type` is the PTV wire code (same stability argument as
 * `MapRouteTypeFilterPreference`: enum case names can be renamed, the int can't drift).
 */
@Serializable
internal data class StoredFollowedTrip(
    @SerialName("run_ref") val runRef: String,
    @SerialName("route_type") val routeTypeCode: Int,
    @SerialName("from_stop_id") val fromStopId: Int? = null,
    @SerialName("route_label") val routeLabel: String? = null,
    @SerialName("destination_name") val destinationName: String,
    @SerialName("completes_at_utc") val completesAtUtc: Instant,
    @SerialName("followed_at_utc") val followedAtUtc: Instant,
)

internal fun FollowedTrip.toStored(): StoredFollowedTrip =
    StoredFollowedTrip(
        runRef = runRef.value,
        routeTypeCode = routeType.toCode(),
        fromStopId = fromStopId?.value,
        routeLabel = routeLabel,
        destinationName = destinationName,
        completesAtUtc = completesAtUtc,
        followedAtUtc = followedAtUtc,
    )

internal fun StoredFollowedTrip.toDomain(): FollowedTrip =
    FollowedTrip(
        runRef = RunRef(runRef),
        routeType = RouteType.fromCode(routeTypeCode),
        fromStopId = fromStopId?.let(::StopId),
        routeLabel = routeLabel,
        destinationName = destinationName,
        completesAtUtc = completesAtUtc,
        followedAtUtc = followedAtUtc,
    )
