package ac.jfx.openptv.core.common

import ac.jfx.openptv.core.model.Coordinates
import kotlinx.coroutines.flow.Flow

/**
 * Project-wide location seam. Production code (the upcoming `:feature:nearby` map and any future
 * "distance-from-me" consumer like favourites' Nearest sort) MUST depend on this interface — never
 * `android.location.LocationManager` or any `com.google.android.gms.*` type — so:
 *
 *  - **GrapheneOS guarantee.** GMS is off the table for the whole project. A single Hilt binding
 *    (default: [ac.jfx.openptv.core.data.LocationManagerLocationProvider]) is the only place the
 *    platform API leaks through.
 *  - **Testability.** Feature tests inject `FakeLocationProvider` from `:core:data-test` via
 *    `@TestInstallIn`; no `LocationManager` shadowing required at the screen level.
 *
 * No Android imports — the contract is pure Kotlin. The interface lives in `:core:common` rather
 * than `:core:data` so ViewModels (which only depend on `:core:domain` / `:core:common`) can
 * accept it directly without dragging in the data module.
 *
 * **Permission boundary.** Both methods assume the caller has already obtained `ACCESS_COARSE_LOCATION`
 * (or finer). The contract for a missing permission is "behave as if there's no fix":
 * [lastKnown] returns `null` and [observe] completes (does not throw). The Compose-side rationale
 * dialog in `:core:designsystem` is the seam that gates the launch of `rememberLauncherForActivityResult`.
 */
interface LocationProvider {
    /**
     * Last cached fix from any enabled provider. Returns `null` if nothing is cached, every
     * provider is disabled, or the permission is missing.
     *
     * Suspend-marked even though `getLastKnownLocation` is a synchronous call, so the impl can
     * switch dispatchers internally (the `LocationManager` query touches a binder).
     */
    suspend fun lastKnown(): Coordinates?

    /**
     * Cold flow of [Coordinates] updates. Emits while the permission is granted and at least one
     * provider is enabled; completes cleanly (does NOT throw) when:
     *
     *  - the user revokes the permission while the flow is collected;
     *  - the only available provider is disabled (no `GPS_PROVIDER` and no `NETWORK_PROVIDER`).
     *
     * Completing rather than throwing is an acceptance criterion for issue #36 — it means
     * downstream operators (`stateIn`, `collectAsStateWithLifecycle`) terminate without an error
     * branch the UI has to handle.
     */
    fun observe(): Flow<Coordinates>
}
