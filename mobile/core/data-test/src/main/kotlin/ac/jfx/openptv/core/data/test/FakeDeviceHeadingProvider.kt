package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.common.DeviceHeadingProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-written fake for [DeviceHeadingProvider]. Sibling to [FakeLocationProvider] — same shape,
 * same test seams ([emit] / [complete]). Backed by per-collector callback channels so a test
 * can drive a sequence of bearings deterministically and complete the flow to simulate a missing
 * compass.
 *
 * Default behaviour on subscribe: no replay. A collector that subscribes before any [emit] sees
 * no values until one fires — matching the production sensor flow's "no signal until the sensor
 * fires" contract. A test that wants the flow to come back with an initial reading just calls
 * [emit] right after subscription.
 */
@Singleton
class FakeDeviceHeadingProvider
    @Inject
    constructor() : DeviceHeadingProvider {
        private val subscribers: CopyOnWriteArrayList<Subscriber> = CopyOnWriteArrayList()

        /** Push a new bearing onto every active `observe()` collector. */
        fun emit(bearingDegrees: Float) {
            subscribers.forEach { it.send(bearingDegrees) }
        }

        /**
         * Simulate "no rotation sensor" mid-stream: completes every active `observe()` collector.
         * Matches the production [SensorManagerDeviceHeadingProvider]'s close-when-null path.
         */
        fun complete() {
            val snapshot = subscribers.toList()
            subscribers.clear()
            snapshot.forEach { it.close() }
        }

        override fun observe(): Flow<Float> =
            callbackFlow {
                val subscriber =
                    Subscriber(
                        send = { degrees -> trySendBlocking(degrees) },
                        close = { close() },
                    )
                subscribers += subscriber
                awaitClose {
                    subscribers.remove(subscriber)
                }
            }

        private class Subscriber(
            val send: (Float) -> Unit,
            val close: () -> Unit,
        )
    }
