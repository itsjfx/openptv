package ac.jfx.openptv.alert

import ac.jfx.openptv.MainActivity
import ac.jfx.openptv.R
import ac.jfx.openptv.core.model.AlightAlert
import ac.jfx.openptv.core.model.FollowedTrip
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.datetime.Instant
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification plumbing for the alight-alert foreground service (issue #201). Two channels:
 *
 * - [CHANNEL_ONGOING] (low importance, silent): the persistent "N stops to X · ETA hh:mm"
 *   status the foreground service is pinned to, with a "Stop following" action.
 * - [CHANNEL_ALERTS] (high importance, sound + vibration): the two alert stages. Separate
 *   channel so the user can tune the loud part (or the quiet part) independently.
 *
 * **Channel ids are a one-way door** — once created on a device, a channel's importance/sound
 * can only be changed by the user, so renaming behaviour means minting a new id and orphaning
 * the old channel's settings. Keep `followed_trip_progress` / `alight_alerts` stable.
 *
 * Stage notifications are posted best-effort: on API 33+ with POST_NOTIFICATIONS denied they
 * are silently dropped (the arming UI already warned about that).
 */
@Singleton
class AlightAlertNotifications
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val manager = NotificationManagerCompat.from(context)

        fun ensureChannels() {
            val ongoing =
                NotificationChannel(
                    CHANNEL_ONGOING,
                    context.getString(R.string.alight_channel_ongoing_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.alight_channel_ongoing_description)
                }
            val alerts =
                NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.alight_channel_alerts_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.alight_channel_alerts_description)
                    enableVibration(true)
                }
            manager.createNotificationChannel(ongoing)
            manager.createNotificationChannel(alerts)
        }

        /** The placeholder the service pins itself to before the first pattern fetch lands. */
        fun trackingNotification(): Notification =
            ongoingBuilder()
                .setContentTitle(context.getString(R.string.alight_ongoing_tracking))
                .build()

        /**
         * The live ongoing status: "N stops to X · ETA hh:mm". [scheduleOnly] appends the
         * "timetable only" warning when there's neither real-time data nor a GPS fix.
         */
        fun ongoingNotification(
            trip: FollowedTrip,
            alert: AlightAlert,
            stopsAway: Int?,
            etaUtc: Instant?,
            scheduleOnly: Boolean,
        ): Notification {
            val title =
                when {
                    stopsAway == null -> context.getString(R.string.alight_ongoing_tracking)
                    stopsAway <= 0 ->
                        context.getString(R.string.alight_ongoing_arriving, alert.stopName)
                    else ->
                        context.resources.getQuantityString(
                            R.plurals.alight_ongoing_stops_away,
                            stopsAway,
                            stopsAway,
                            alert.stopName,
                        )
                }
            val text =
                etaUtc?.let {
                    context.getString(
                        if (scheduleOnly) R.string.alight_ongoing_schedule_only else R.string.alight_ongoing_eta,
                        formatLocalTime(it),
                    )
                }
            return ongoingBuilder()
                .setContentTitle(title)
                .apply { text?.let(::setContentText) }
                .setSubText(trip.routeLabel)
                .build()
        }

        fun updateOngoing(notification: Notification) {
            notifyIfAllowed(ONGOING_NOTIFICATION_ID, notification)
        }

        /** Stage 1 — vehicle is one stop before the alight stop. */
        fun postApproachAlert(alert: AlightAlert) {
            val notification =
                alertBuilder()
                    .setContentTitle(context.getString(R.string.alight_approach_title, alert.stopName))
                    .setContentText(context.getString(R.string.alight_approach_text))
                    .build()
            notifyIfAllowed(APPROACH_NOTIFICATION_ID, notification)
        }

        /** Stage 2 — ~15 s before arrival at the alight stop. */
        fun postArrivalAlert(alert: AlightAlert) {
            val notification =
                alertBuilder()
                    .setContentTitle(context.getString(R.string.alight_arrival_title))
                    .setContentText(context.getString(R.string.alight_arrival_text, alert.stopName))
                    .build()
            notifyIfAllowed(ARRIVAL_NOTIFICATION_ID, notification)
        }

        /** Teardown (unfollow / completion): drop any stage alerts still sitting in the shade. */
        fun cancelAlerts() {
            manager.cancel(APPROACH_NOTIFICATION_ID)
            manager.cancel(ARRIVAL_NOTIFICATION_ID)
        }

        private fun ongoingBuilder(): NotificationCompat.Builder =
            NotificationCompat.Builder(context, CHANNEL_ONGOING)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent())
                .addAction(
                    0,
                    context.getString(R.string.alight_stop_following),
                    stopFollowingIntent(),
                )

        private fun alertBuilder(): NotificationCompat.Builder =
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())

        private fun openAppIntent(): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun stopFollowingIntent(): PendingIntent =
            PendingIntent.getService(
                context,
                1,
                Intent(context, AlightAlertService::class.java)
                    .setAction(AlightAlertService.ACTION_STOP_FOLLOWING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun notifyIfAllowed(
            id: Int,
            notification: Notification,
        ) {
            // API 33+: posting without POST_NOTIFICATIONS throws SecurityException via the
            // compat layer's permission check; the arming flow asked contextually, but the
            // user can revoke at any time — degrade to silence, never crash the service.
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED ||
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
            ) {
                manager.notify(id, notification)
            }
        }

        private fun formatLocalTime(instant: Instant): String =
            DateFormat.getTimeFormat(context).format(Date(instant.toEpochMilliseconds()))

        companion object {
            /** One-way door: created channels keep user-tuned settings keyed by this id. */
            const val CHANNEL_ONGOING: String = "followed_trip_progress"

            /** One-way door: created channels keep user-tuned settings keyed by this id. */
            const val CHANNEL_ALERTS: String = "alight_alerts"

            const val ONGOING_NOTIFICATION_ID: Int = 2001
            const val APPROACH_NOTIFICATION_ID: Int = 2002
            const val ARRIVAL_NOTIFICATION_ID: Int = 2003
        }
    }
