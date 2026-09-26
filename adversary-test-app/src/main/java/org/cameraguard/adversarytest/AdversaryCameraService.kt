package org.cameraguard.adversarytest

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AdversaryCameraService : Service() {

    companion object {
        const val TAG = "AdversaryTestApp"
        private const val CHANNEL_ID = "adversary_camera_service_channel"
        private const val NOTIFICATION_ID = 4001

        const val ACTION_START_A4 = "org.cameraguard.adversarytest.action.START_A4"
        const val ACTION_STOP = "org.cameraguard.adversarytest.action.STOP"
        const val EXTRA_HOLD_DURATION_MS = "extra_hold_duration_ms"
        const val EXTRA_DELAY_BEFORE_OPEN_MS = "extra_delay_before_open_ms"
    }

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var activeCameraDevice: CameraDevice? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val thread = HandlerThread("AdversaryCameraThread").apply { start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_A4
        if (action == ACTION_STOP) {
            stopCameraAndService()
            return START_NOT_STICKY
        }

        val holdDurationMs = intent?.getLongExtra(EXTRA_HOLD_DURATION_MS, 2000L) ?: 2000L
        val delayBeforeOpenMs = intent?.getLongExtra(EXTRA_DELAY_BEFORE_OPEN_MS, 500L) ?: 500L

        startForegroundWithNotification()

        serviceScope.launch {
            if (delayBeforeOpenMs > 0) {
                delay(delayBeforeOpenMs)
            }
            executeServiceCameraAccess(holdDurationMs)
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Adversary Camera Service")
            .setContentText("Controlled camera background service active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundServiceType
        )
    }

    @SuppressLint("MissingPermission")
    private fun executeServiceCameraAccess(holdDurationMs: Long) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            Log.e(TAG, "[ACCESS_RESULT] scenario=A4 groundTruth=FAILED detail=CameraManager unavailable")
            stopCameraAndService()
            return
        }

        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull() ?: "0"
        } catch (e: Exception) {
            "0"
        }

        Log.i(TAG, "[ACCESS_ATTEMPT] scenario=A4 cameraId=$cameraId component=AdversaryCameraService")

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    activeCameraDevice = camera
                    Log.i(TAG, "[ACCESS_RESULT] scenario=A4 groundTruth=SUCCESS detail=Camera opened from Foreground Service")
                    serviceScope.launch {
                        delay(holdDurationMs)
                        Log.i(TAG, "[ACCESS_CLOSURE] scenario=A4 durationMs=$holdDurationMs")
                        stopCameraAndService()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.i(TAG, "[ACCESS_RESULT] scenario=A4 groundTruth=FAILED detail=Camera disconnected in service")
                    stopCameraAndService()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val isPolicyBlocked = (error == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED)
                    val groundTruth = if (isPolicyBlocked) "BLOCKED_BY_PLATFORM" else "FAILED"
                    val errorDetail = when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED (Blocked by platform policy)"
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE"
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE"
                        else -> "UNKNOWN_ERROR_$error"
                    }
                    Log.e(TAG, "[ACCESS_RESULT] scenario=A4 groundTruth=$groundTruth detail=$errorDetail")
                    stopCameraAndService()
                }
            }, cameraHandler)
        } catch (se: SecurityException) {
            Log.e(TAG, "[ACCESS_RESULT] scenario=A4 groundTruth=BLOCKED_BY_PLATFORM detail=SecurityException: ${se.message}")
            stopCameraAndService()
        } catch (cae: CameraAccessException) {
            val groundTruth = if (cae.reason == CameraAccessException.CAMERA_DISABLED) "BLOCKED_BY_PLATFORM" else "FAILED"
            Log.e(TAG, "[ACCESS_RESULT] scenario=A4 groundTruth=$groundTruth detail=CameraAccessException(reason=${cae.reason}): ${cae.message}")
            stopCameraAndService()
        } catch (e: Exception) {
            Log.e(TAG, "[ACCESS_RESULT] scenario=A4 groundTruth=FAILED detail=Exception: ${e.message}")
            stopCameraAndService()
        }
    }

    private fun stopCameraAndService() {
        try {
            activeCameraDevice?.close()
        } catch (_: Exception) {}
        activeCameraDevice = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCameraAndService()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Adversary Camera Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
