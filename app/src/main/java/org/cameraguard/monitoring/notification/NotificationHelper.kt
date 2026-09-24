package org.cameraguard.monitoring.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.cameraguard.MainActivity
import org.cameraguard.R
import org.cameraguard.data.model.CameraAccessEvent

class NotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Persistent service channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "CameraGuard Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays ongoing status while camera privacy monitoring is active"
                setShowBadge(false)
            }

            // High-priority alert channel
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                "Suspicious Camera Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when unexpected or unverified camera activity is detected"
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    /**
     * Builds the persistent ongoing notification required for Android Foreground Services.
     */
    fun buildServiceNotification(): Notification {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_SERVICE,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE_ID)
            .setContentTitle("CameraGuard Active")
            .setContentText("Monitoring camera availability and privacy context")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Dispatches an actionable notification when unexpected camera activity is observed.
     */
    fun showUnexpectedActivityAlert(event: CameraAccessEvent) {
        if (!hasNotificationPermission()) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TO_HISTORY, true)
            putExtra(EXTRA_EVENT_ID, event.id)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            event.id.hashCode(),
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val appName = event.inferredPackageName ?: "Unknown application"
        val alertText = "Unexpected camera transition detected (${event.rawEventType.name}) by $appName."

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS_ID)
            .setContentTitle("⚠️ Unexpected Camera Activity")
            .setContentText(alertText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$alertText\n\nReason: ${event.classificationExplanation}")
            )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(event.id.hashCode(), notification)
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        const val CHANNEL_SERVICE_ID = "camera_monitoring_service"
        const val CHANNEL_ALERTS_ID = "camera_alerts"
        const val SERVICE_NOTIFICATION_ID = 1001
        private const val REQUEST_CODE_SERVICE = 2001

        const val EXTRA_NAVIGATE_TO_HISTORY = "extra_navigate_to_history"
        const val EXTRA_EVENT_ID = "extra_event_id"
    }
}
