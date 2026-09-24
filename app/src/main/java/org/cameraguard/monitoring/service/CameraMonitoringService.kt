package org.cameraguard.monitoring.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.cameraguard.data.model.AccessClassification
import org.cameraguard.monitoring.CameraAvailabilityTracker
import org.cameraguard.monitoring.CameraMonitor
import org.cameraguard.monitoring.notification.NotificationHelper

class CameraMonitoringService : Service() {

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var tracker: CameraAvailabilityTracker

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        tracker = CameraAvailabilityTracker(this)

        tracker.onEventDetected = { event ->
            if (event.classification == AccessClassification.UNEXPECTED) {
                notificationHelper.showUnexpectedActivityAlert(event)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_MONITORING -> {
                stopForegroundMonitoring()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundMonitoring()
                return START_STICKY
            }
        }
    }

    private fun startForegroundMonitoring() {
        val notification = notificationHelper.buildServiceNotification()

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NotificationHelper.SERVICE_NOTIFICATION_ID,
            notification,
            foregroundType
        )

        tracker.startTracking()
    }

    private fun stopForegroundMonitoring() {
        tracker.stopTracking()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopForegroundMonitoring()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_MONITORING = "org.cameraguard.action.START_MONITORING"
        const val ACTION_STOP_MONITORING = "org.cameraguard.action.STOP_MONITORING"

        fun startService(context: Context) {
            val intent = Intent(context, CameraMonitoringService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CameraMonitoringService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            context.startService(intent)
        }
    }
}
