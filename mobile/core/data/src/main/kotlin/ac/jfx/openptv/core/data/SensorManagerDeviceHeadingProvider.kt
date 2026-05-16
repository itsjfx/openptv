package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.DeviceHeadingProvider
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [DeviceHeadingProvider]. Reads `TYPE_ROTATION_VECTOR` from [SensorManager] and converts
 * the quaternion into a heading in degrees clockwise from north.
 *
 * **Why rotation vector, not magnetic field + accelerometer.** The rotation-vector sensor is
 * already a fused / filtered orientation Android exposes since API 9 — using it avoids us having
 * to roll our own complementary filter on top of raw mag + accel. Same heading value Google's own
 * compass apps surface; on devices without a magnetometer the platform synthesises it from
 * gyroscope + accelerometer (less accurate but never zero). When the device has no rotation
 * sensor at all (some stripped emulator builds), `getDefaultSensor` returns `null` and we close
 * the flow — matching the [DeviceHeadingProvider.observe] contract.
 *
 * **Sampling rate.** `SENSOR_DELAY_UI` (~60 ms) is the cheapest cadence that still updates the
 * map cone smoothly as the user turns. `SENSOR_DELAY_GAME` would be twice as fast but burns
 * battery for no visible UX gain — the map's icon-rotation animation is the bottleneck, not the
 * sensor cadence.
 */
@Singleton
internal class SensorManagerDeviceHeadingProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : DeviceHeadingProvider {
        private val sensorManager: SensorManager =
            context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        override fun observe(): Flow<Float> =
            callbackFlow {
                val rotationSensor: Sensor? =
                    sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                if (rotationSensor == null) {
                    // No rotation sensor — typical on stripped emulator images. Close cleanly so
                    // the ViewModel treats this as "no heading available" rather than an error.
                    close()
                    return@callbackFlow
                }

                // Pre-allocate the rotation-matrix and orientation arrays so each onSensorChanged
                // callback doesn't churn the GC. `SensorManager` documents the buffer sizes (9 for
                // the matrix, 3 for orientation) so the allocation is fixed.
                val rotationMatrix = FloatArray(ROTATION_MATRIX_SIZE)
                val orientation = FloatArray(ORIENTATION_VALUES_SIZE)

                val listener =
                    object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            // `getRotationMatrixFromVector` accepts a 4-element rotation vector and
                            // writes a 3x3 column-major rotation matrix. We then ask
                            // `getOrientation` for the azimuth in radians; azimuth is the angle
                            // around the world -Z axis (i.e. heading clockwise from north).
                            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                            SensorManager.getOrientation(rotationMatrix, orientation)
                            val azimuthRadians = orientation[ORIENTATION_AZIMUTH_INDEX]
                            val azimuthDegrees = Math.toDegrees(azimuthRadians.toDouble()).toFloat()
                            // Normalise into [0, 360) — Android's azimuth comes back in
                            // [-180, 180], MapLibre's `iconRotate` expects a clockwise-from-north
                            // value where wrapping at 360 is fine but negatives flip the rotation.
                            val normalised = ((azimuthDegrees % FULL_TURN) + FULL_TURN) % FULL_TURN
                            trySend(normalised)
                        }

                        override fun onAccuracyChanged(
                            sensor: Sensor?,
                            accuracy: Int,
                        ) {
                            // We don't surface accuracy to the UI for v1. A low-accuracy reading is
                            // still better than no cone on a quick "which way am I facing" glance.
                        }
                    }

                sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
                awaitClose { sensorManager.unregisterListener(listener) }
            }

        private companion object {
            // SensorManager documents these buffer sizes — surfaced as named constants so detekt's
            // MagicNumber rule stays quiet.
            const val ROTATION_MATRIX_SIZE: Int = 9
            const val ORIENTATION_VALUES_SIZE: Int = 3

            // Index of azimuth (heading) inside the orientation array — the other two are pitch
            // and roll.
            const val ORIENTATION_AZIMUTH_INDEX: Int = 0

            // A full turn in degrees. Used to normalise the azimuth into [0, 360).
            const val FULL_TURN: Float = 360f
        }
    }
