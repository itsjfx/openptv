package ac.jfx.openptv.alert

import ac.jfx.openptv.core.common.LocationProvider
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.FollowedTripRepository
import ac.jfx.openptv.core.domain.AlightAlertEvaluation
import ac.jfx.openptv.core.domain.AlightAlertEvaluator
import ac.jfx.openptv.core.domain.ObserveRunPatternUseCase
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.FollowedTrip
import ac.jfx.openptv.core.model.RunPattern
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Foreground service that tracks the followed trip's armed alight alert while the app is closed
 * (issue #201). Deliberately thin: every stage decision lives in the pure
 * [AlightAlertEvaluator]; this class just loops fetch → evaluate → notify → persist → sleep.
 *
 * **Lifecycle.** Started by the app layer whenever the followed trip carries an alert (see
 * `MainNav`); `START_STICKY` so a system kill re-arms it. It stops *itself* the moment the
 * stored trip loses its alert — unfollow (from the pinned bar, the run-pattern screen, or the
 * notification's "Stop following" action), disarm, or trip completion all funnel through the
 * repository, which is the single source of truth the [track] loop watches.
 *
 * **Polling.** Each cycle takes the first non-Loading emission from the shared
 * [ObserveRunPatternUseCase] flow — a one-shot fetch that reuses the whole run-pattern data
 * path (mappers, geopath/coordinate join) — then sleeps for the evaluator's adaptive
 * [AlightAlertEvaluation.nextCheckIn]. Fired stages are persisted onto the stored trip
 * immediately, so a service restart never re-fires; `completesAtUtc` is refreshed on every
 * successful fetch, keeping #200's completion eviction honest while the app is closed.
 *
 * **GPS fallback.** When a fetch shows no real-time signal (trams) and location permission is
 * granted, a [LocationProvider] collection starts (`LocationManager` only — GrapheneOS
 * constraint) and every fix re-evaluates against the last pattern between polls. The
 * foreground-service type is upgraded to include `location` at that point; if the platform
 * refuses (app in the background on API 34+), the fallback is skipped and alerts stay
 * time-based — degraded, never crashed.
 */
@AndroidEntryPoint
class AlightAlertService : Service() {
    @Inject lateinit var followedTripRepository: FollowedTripRepository

    @Inject lateinit var observeRunPattern: ObserveRunPatternUseCase

    @Inject lateinit var evaluator: AlightAlertEvaluator

    @Inject lateinit var locationProvider: LocationProvider

    @Inject lateinit var notifications: AlightAlertNotifications

    @Inject lateinit var clock: Clock

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var watchJob: Job? = null
    private var trackingLoop: Job? = null
    private var locationJob: Job? = null

    /** Serialises evaluations (poll loop vs. GPS fix) so the fire-once latches can't race. */
    private val evaluationMutex = Mutex()

    private var lastPattern: RunPattern? = null
    private var lastFix: Coordinates? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForegroundCompat(withLocation = hasLocationPermission())
        if (intent?.action == ACTION_STOP_FOLLOWING) {
            // The repository write flips the watched flow to null, which stops the service —
            // one teardown path no matter who initiates it.
            scope.launch { followedTripRepository.unfollow() }
            return START_NOT_STICKY
        }
        if (watchJob == null) {
            watchJob =
                scope.launch {
                    followedTripRepository.followedTrip
                        .map { trip -> trip?.takeIf { it.alightAlert != null }?.trackingKey() }
                        .distinctUntilChanged()
                        .collect { key ->
                            // Re-arming (new key) cancels the old loop; latch-persisting writes
                            // keep the same key and leave the loop running.
                            locationJob?.cancel()
                            locationJob = null
                            trackingLoop?.cancel()
                            trackingLoop = null
                            if (key == null) {
                                notifications.cancelAlerts()
                                stopSelf()
                            } else {
                                trackingLoop = scope.launch { track() }
                            }
                        }
                }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The fetch → evaluate → sleep loop for the currently armed alert. Exits via cancellation
     * (the alert changed or went away) — completion also lands here as an unfollow write.
     */
    private suspend fun track() {
        while (true) {
            val trip = followedTripRepository.followedTrip.first() ?: return
            if (trip.alightAlert == null) return
            if (trip.isComplete(clock.now())) {
                followedTripRepository.unfollow()
                return
            }
            val result = observeRunPattern(trip.runRef, trip.routeType).firstOrNull { it !is Result.Loading }
            val pattern = (result as? Result.Success)?.data?.takeIf { it.stops.isNotEmpty() }
            if (pattern == null) {
                // Network error or empty pattern: keep the last notification, retry later.
                delay(RETRY_DELAY)
                continue
            }
            lastPattern = pattern
            val evaluation = evaluateAndPublish(pattern) ?: return
            maybeStartGpsFallback(evaluation)
            delay(evaluation.nextCheckIn)
        }
    }

    /**
     * One serialised evaluation pass: read the freshest stored trip (latches may have advanced
     * on another path), evaluate, update the ongoing notification, sound due stages, persist.
     * Returns null when the trip/alert vanished mid-pass.
     */
    private suspend fun evaluateAndPublish(pattern: RunPattern): AlightAlertEvaluation? =
        evaluationMutex.withLock {
            val trip = followedTripRepository.followedTrip.first() ?: return null
            val alert = trip.alightAlert ?: return null
            val evaluation = evaluator.evaluate(pattern, alert, clock.now(), lastFix)

            notifications.updateOngoing(
                notifications.ongoingNotification(
                    trip = trip,
                    alert = evaluation.updatedAlert,
                    stopsAway = evaluation.stopsAway,
                    etaUtc = evaluation.etaUtc,
                    scheduleOnly = evaluation.isScheduleOnly,
                ),
            )
            if (evaluation.fireApproachAlert) notifications.postApproachAlert(evaluation.updatedAlert)
            if (evaluation.fireArrivalAlert) notifications.postArrivalAlert(evaluation.updatedAlert)

            val terminus = pattern.stops.last()
            val refreshed =
                trip.copy(
                    alightAlert = evaluation.updatedAlert,
                    completesAtUtc = terminus.estimatedDepartureUtc ?: terminus.scheduledDepartureUtc,
                )
            if (refreshed != trip) followedTripRepository.follow(refreshed)
            evaluation
        }

    /**
     * Start the GPS fallback once: no real-time signal, permission granted, not already
     * collecting. Each fix re-evaluates against the last pattern so proximity stages fire
     * between polls, not just on them.
     */
    private fun maybeStartGpsFallback(evaluation: AlightAlertEvaluation) {
        if (evaluation.hasRealTimeSignal || locationJob != null || !hasLocationPermission()) return
        startForegroundCompat(withLocation = true)
        locationJob =
            scope.launch {
                locationProvider.observe().collect { fix ->
                    lastFix = fix
                    lastPattern?.let { evaluateAndPublish(it) }
                }
            }
    }

    private fun startForegroundCompat(withLocation: Boolean) {
        val type =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                if (withLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(
                this,
                AlightAlertNotifications.ONGOING_NOTIFICATION_ID,
                notifications.trackingNotification(),
                type,
            )
        } catch (_: IllegalStateException) {
            // API 34+ refuses a location-typed (re)start from the background. Fall back to
            // dataSync only — the alert degrades to time-based rather than dying.
            if (withLocation) startForegroundCompat(withLocation = false)
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun FollowedTrip.trackingKey(): Triple<String, Int, Int?> =
        Triple(runRef.value, routeType.toCode(), alightAlert?.stopId?.value)

    companion object {
        /** The ongoing notification's "Stop following" action. */
        const val ACTION_STOP_FOLLOWING: String = "ac.jfx.openptv.alert.STOP_FOLLOWING"

        private val RETRY_DELAY = 30.seconds
    }
}
