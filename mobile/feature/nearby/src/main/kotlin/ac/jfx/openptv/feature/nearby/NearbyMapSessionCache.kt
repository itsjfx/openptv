package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-lifetime cache for the Nearby map (issue #146). [NearbyViewModel] is scoped to its
 * navigation entry, so leaving the map (tapping a stop, or entering via stop-detail's
 * "show on map" destination and popping back) destroys the ViewModel — and with it, previously,
 * the fetched pins and the camera position. Re-entry then rendered an empty map at the default
 * viewport and refetched everything, which read as a "full refresh".
 *
 * Hoisting the stop LRU + the last settled camera into a `@Singleton` means a fresh ViewModel
 * seeds its first `Loaded` state from wherever the user left off: cached pins render
 * immediately and the camera reopens on the previous viewport, while the normal camera-idle
 * fetch still refreshes the data underneath.
 *
 * Deliberately NOT persisted to disk — stops are ephemeral live data (single source of truth is
 * the network, per the architecture doc); this is a process-lifetime warm cache only. The filter
 * toggle still clears [stops] so stale-filter pins never outlive the user's intent, and the
 * route-type filter itself is persisted separately via DataStore (issue #112).
 */
@Singleton
class NearbyMapSessionCache
    @Inject
    constructor() {
        internal val stops = LruStopCache(MAX_CACHED_STOPS)

        /**
         * The camera of the last accepted idle (or programmatic focus) — i.e. the viewport the
         * user last actually looked at. Null until the map has settled once this process.
         */
        internal var lastCamera: OpenPtvCameraState? = null

        internal companion object {
            /**
             * Cap on the LRU stop cache. Sized so a long session of pan/zoom across metropolitan
             * Melbourne wouldn't evict — a single screen-width of dense CBD fetches a few hundred
             * stops, and now that the map renders pins unclustered (issue #124) the cache is also
             * what populates a zoomed-out view: the more stops we retain, the more of the network
             * the user sees when they pull back. Raised from 2000 to 10000 alongside the
             * clustering removal so a metro-wide zoom-out stays densely populated instead of
             * thinning out to whatever the last few fetches returned.
             *
             * Far below memory pressure: each `Stop` is a handful of strings + two doubles
             * (~150 bytes), so 10000 entries is ~1.5 MB. MapLibre renders this many unclustered
             * circle features comfortably.
             */
            internal const val MAX_CACHED_STOPS: Int = 10_000
        }
    }

/**
 * Tiny insertion-order LRU keyed by [StopId]. A re-insert (i.e. a stop returned by a fresh fetch
 * we've already seen) bumps its recency by removing-then-re-adding under the same key. Eviction
 * fires synchronously on [putAll] / [put] once the size exceeds the bound.
 *
 * Not thread-safe — only touched from the ViewModel's coroutine scope (single Dispatcher.Main).
 * If that changes, wrap accesses in a Mutex; for now the cost of synchronisation is unjustified.
 */
internal class LruStopCache(private val maxSize: Int) {
    private val backing: LinkedHashMap<StopId, Stop> = LinkedHashMap()

    fun putAll(stops: Collection<Stop>) {
        stops.forEach(::put)
    }

    fun put(stop: Stop) {
        // Remove-then-add bumps recency for a stop we've already cached. LinkedHashMap with
        // `accessOrder = true` would also work, but we'd still need the explicit eviction.
        backing.remove(stop.id)
        backing[stop.id] = stop
        while (backing.size > maxSize) {
            val eldest = backing.keys.iterator().next()
            backing.remove(eldest)
        }
    }

    fun clear() {
        backing.clear()
    }

    fun snapshot(): List<Stop> = backing.values.toList()

    fun size(): Int = backing.size
}
