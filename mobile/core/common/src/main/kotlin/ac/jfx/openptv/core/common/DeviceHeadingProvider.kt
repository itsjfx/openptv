package ac.jfx.openptv.core.common

import kotlinx.coroutines.flow.Flow

/**
 * Project-wide compass / heading seam. Sibling to [LocationProvider] — same shape, same contract:
 * pure Kotlin interface in `:core:common` so ViewModels can depend on it without dragging Android
 * imports, with the platform-touching impl bound by Hilt over in `:core:data`.
 *
 * **Why a seam.** Heading is the second half of issue #99's "where am I + which way am I facing"
 * — paired with the [LocationProvider] dot. Production reads `SensorManager.TYPE_ROTATION_VECTOR`;
 * tests inject a fake to drive the bearing without booting Android sensors. No GMS involved,
 * matching the GrapheneOS constraint that already governs the location stack.
 *
 * **Bearing units.** Degrees clockwise from true north, normalised to `[0, 360)`. The MapLibre
 * `SymbolLayer.iconRotate` property uses the same convention (0 = north, 90 = east), so the
 * ViewModel can forward the value straight through to the map without conversion.
 *
 * **No-compass devices.** Some devices (or emulators) don't have a rotation sensor. The flow
 * completes cleanly without emitting in that case — see [observe]. The screen interprets "no
 * emission yet" as "no cone", showing just the dot.
 */
interface DeviceHeadingProvider {
    /**
     * Cold flow of device heading in degrees clockwise from north, normalised to `[0, 360)`.
     *
     * Completes cleanly (does NOT throw) when:
     *  - the device has no rotation-vector sensor (e.g. a stripped emulator);
     *  - the sensor is otherwise unavailable.
     *
     * Mirrors the contract of [LocationProvider.observe] so the ViewModel can treat both seams
     * the same way — completion = "no signal", no error branch to handle.
     */
    fun observe(): Flow<Float>
}
