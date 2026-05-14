package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.common.LocationProvider
import ac.jfx.openptv.core.model.Coordinates
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-written fake for [LocationProvider]. Backed by an in-memory [MutableStateFlow] for
 * "current fix" lookups and a per-collector channel fan-out for `observe()` emissions.
 *
 * Test seams:
 *
 *  - [seed]: replace `lastKnown()` without notifying observers (matches a fresh-process replay).
 *  - [emit]: push a fix to every active `observe()` collector AND update `lastKnown()` (matches a
 *    fresh fix arriving from a real provider).
 *  - [complete]: close every active collector, mirroring `LocationManagerLocationProvider`'s
 *    `close()` path on permission revocation or provider disable.
 *
 * Per-collector channels (rather than a single SharedFlow) give us deterministic completion
 * semantics: `complete()` closes the channel, the `for (coords in channel)` loop falls through,
 * and the `callbackFlow` finishes — same shape the production callbackFlow exposes.
 *
 * `@Singleton` so a `setUp()` mutation lands on the same instance the ViewModel's Hilt graph
 * collects from. Mirrors [FakeFavouritesRepository].
 */
@Singleton
class FakeLocationProvider
    @Inject
    constructor() : LocationProvider {
        private val state: MutableStateFlow<Coordinates?> = MutableStateFlow(MELBOURNE_CBD)
        private val subscribers: CopyOnWriteArrayList<Subscriber> = CopyOnWriteArrayList()

        /** Replace the stored "last known" without emitting on `observe()` flow. */
        fun seed(coordinates: Coordinates?) {
            state.value = coordinates
        }

        /** Push a new coordinate onto both `lastKnown()` and every active `observe()` collector. */
        fun emit(coordinates: Coordinates) {
            state.value = coordinates
            subscribers.forEach { it.send(coordinates) }
        }

        /**
         * Simulate permission revocation mid-stream: completes every active `observe()` collector.
         * Idempotent — collectors that already completed are removed from the registry on close.
         */
        fun complete() {
            val snapshot = subscribers.toList()
            subscribers.clear()
            snapshot.forEach { it.close() }
        }

        override suspend fun lastKnown(): Coordinates? = state.value

        override fun observe(): Flow<Coordinates> =
            callbackFlow {
                val subscriber =
                    Subscriber(
                        send = { coords -> trySendBlocking(coords) },
                        close = { close() },
                    )
                subscribers += subscriber

                // Replay current state once so a collector that subscribes after a seed/emit gets
                // the latest fix immediately. Production callbackFlow doesn't do this
                // (LocationManager only fires on new fixes), but for tests it eliminates a class
                // of "fake didn't seed before subscribe" timing flakes.
                state.value?.let { subscriber.send(it) }

                awaitClose {
                    subscribers.remove(subscriber)
                }
            }

        private class Subscriber(
            val send: (Coordinates) -> Unit,
            val close: () -> Unit,
        )

        private companion object {
            // Default fix: Melbourne CBD centroid (-37.8136 / 144.9631) — matches the auto-memory's
            // manual smoke-test command `adb emu geo fix -37.8136 144.9631`.
            val MELBOURNE_CBD: Coordinates = Coordinates(lat = -37.8136, lng = 144.9631)
        }
    }
