package org.cameratestharness

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cameratestharness.experiment.ExperimentLogger
import java.util.concurrent.Executor

class CameraTestService : Service() {

    companion object {
        private const val TAG = "CameraTestHarness"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "camera_test_harness_channel"
        private const val CHANNEL_NAME = "Camera Test Harness Service"

        const val ACTION_START = "org.cameratestharness.action.START"
        const val ACTION_STOP = "org.cameratestharness.action.STOP"
        const val ACTION_ARM_AUTOMATED_TRIGGER = "org.cameratestharness.action.ARM_AUTOMATED_TRIGGER"
        const val EXTRA_TRIGGER_DELAY_MS = "org.cameratestharness.extra.TRIGGER_DELAY_MS"
        const val DEFAULT_TRIGGER_DELAY_MS = 5000L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _harnessState = MutableStateFlow(HarnessState.IDLE)
        val harnessState: StateFlow<HarnessState> = _harnessState.asStateFlow()

        private val _serviceStatus = MutableStateFlow("Simulation IDLE")
        val serviceStatus: StateFlow<String> = _serviceStatus.asStateFlow()
    }

    private val cameraLock = Any()
    private var isSimulationRequested = false
    private var isSessionActive = false
    private var isAutomatedTriggerArmed = false
    private var isAutomatedSession = false
    private var pendingTriggerRunnable: Runnable? = null
    private var isStopping = false

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var selectedCameraId: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "[CameraTestHarness] Foreground service started")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                Log.i(TAG, "[CameraTestHarness] Stop requested")
                stopSimulation()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                Log.i(TAG, "[CameraTestHarness] Simulation start")
                startSimulation()
                return START_STICKY
            }
            ACTION_ARM_AUTOMATED_TRIGGER -> {
                Log.i(TAG, "[CameraTestHarness] Automated trigger arm requested")
                val delayMs = intent?.getLongExtra(EXTRA_TRIGGER_DELAY_MS, DEFAULT_TRIGGER_DELAY_MS) ?: DEFAULT_TRIGGER_DELAY_MS
                armAutomatedTrigger(delayMs)
                return START_STICKY
            }
            else -> {
                Log.w(TAG, "[CameraTestHarness] Unknown action received: $action")
                return START_NOT_STICKY
            }
        }
    }

    private fun startSimulation() {
        synchronized(cameraLock) {
            if (isSimulationRequested || isSessionActive) {
                Log.w(TAG, "[CameraTestHarness] Simulation already active")
                return
            }
            isSimulationRequested = true
            isSessionActive = true
            isStopping = false
        }

        _harnessState.value = HarnessState.STARTING
        _serviceStatus.value = "Starting foreground service..."
        val rep = ExperimentLogger.repetition.value
        ExperimentLogger.recordEvent(
            userAction = "CAMERA_OPEN_REQUESTED",
            cameraEvent = "CAMERA_OPEN_REQUESTED",
            foregroundServiceActive = true,
            foregroundServiceType = "camera",
            sessionState = "STARTING",
            notes = "rep=$rep;trigger=user_start"
        )

        // 1. Establish foreground service notification
        try {
            val notification = buildNotification("Controlled Camera Test Service is acquiring camera...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            _isRunning.value = true
        } catch (e: Exception) {
            Log.e(TAG, "[CameraTestHarness] Failed to enter foreground service", e)
            _harnessState.value = HarnessState.ERROR
            _serviceStatus.value = "Failed to start foreground service: ${e.message}"
            stopSelf()
            return
        }

        acquireAndOpenHardware(isAutomated = false)
    }

    private fun armAutomatedTrigger(delayMs: Long = DEFAULT_TRIGGER_DELAY_MS) {
        synchronized(cameraLock) {
            if (isSimulationRequested || isSessionActive) {
                Log.w(TAG, "[CameraTestHarness] Simulation already active")
                return
            }
            isSimulationRequested = true
            isAutomatedTriggerArmed = true
            isStopping = false
        }

        _harnessState.value = HarnessState.ARMED
        val delaySec = delayMs / 1000
        val rep = ExperimentLogger.repetition.value
        _serviceStatus.value = "Automated trigger armed: acquiring camera in ${delaySec}s..."

        ExperimentLogger.recordEvent(
            userAction = "ARM_AUTOMATED_TRIGGER",
            cameraEvent = "NONE",
            foregroundServiceActive = true,
            foregroundServiceType = "camera",
            sessionState = "ARMED",
            notes = "rep=$rep;trigger=countdown_timer;delay_sec=$delaySec;Armed countdown"
        )

        // Establish foreground service notification immediately while app is visible
        try {
            val notification = buildNotification("Automated trigger armed (${delaySec}s countdown)... Switch to Home/Background")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            _isRunning.value = true
        } catch (e: Exception) {
            Log.e(TAG, "[CameraTestHarness] Failed to enter foreground service", e)
            _harnessState.value = HarnessState.ERROR
            _serviceStatus.value = "Failed to start foreground service: ${e.message}"
            stopSelf()
            return
        }

        startCameraThread()

        val runnable = Runnable {
            synchronized(cameraLock) {
                if (!isSimulationRequested || isStopping) {
                    Log.i(TAG, "[CameraTestHarness] Automated trigger aborted before execution")
                    return@Runnable
                }
                isSessionActive = true
                isAutomatedTriggerArmed = false
            }

            Log.i(TAG, "[CameraTestHarness] Automated trigger fired! Initiating camera acquisition")
            _harnessState.value = HarnessState.STARTING
            _serviceStatus.value = "Automated trigger fired. Acquiring camera hardware..."

            ExperimentLogger.recordEvent(
                userAction = "AUTOMATED_TRIGGER_FIRED",
                cameraEvent = "CAMERA_OPEN_REQUESTED",
                foregroundServiceActive = true,
                foregroundServiceType = "camera",
                sessionState = "STARTING",
                recentUserInteraction = false,
                notes = "rep=$rep;trigger=countdown_timer;delay_sec=$delaySec;Automated background trigger fired"
            )

            acquireAndOpenHardware(isAutomated = true)
        }

        pendingTriggerRunnable = runnable
        cameraHandler?.postDelayed(runnable, delayMs)
    }

    private fun acquireAndOpenHardware(isAutomated: Boolean) {
        isAutomatedSession = isAutomated

        // Check CAMERA permission
        Log.i(TAG, "[CameraTestHarness] Camera permission check (isAutomated=$isAutomated)")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "[CameraTestHarness] Camera permission missing")
            _harnessState.value = HarnessState.ERROR
            _serviceStatus.value = "Camera permission not granted"
            ExperimentLogger.recordEvent(
                cameraEvent = "NONE",
                cameraPermission = "DENIED",
                foregroundServiceActive = false,
                sessionState = "ERROR",
                notes = "Camera permission missing, acquisition halted"
            )
            stopSimulation()
            return
        }

        // Start Camera Background Thread
        startCameraThread()

        // Acquire CameraManager and choose Camera
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            Log.e(TAG, "[CameraTestHarness] CameraManager unavailable")
            _harnessState.value = HarnessState.ERROR
            _serviceStatus.value = "CameraManager not available"
            stopSimulation()
            return
        }

        val chosenId = selectCameraId(cameraManager)
        if (chosenId == null) {
            Log.e(TAG, "[CameraTestHarness] No suitable camera found")
            _harnessState.value = HarnessState.ERROR
            _serviceStatus.value = "No available camera found"
            stopSimulation()
            return
        }

        selectedCameraId = chosenId
        openSelectedCamera(cameraManager, chosenId)
    }

    private fun selectCameraId(cameraManager: CameraManager): String? {
        return try {
            val idList = cameraManager.cameraIdList
            Log.i(TAG, "[CameraTestHarness] Available camera IDs: ${idList.joinToString()}")

            if (idList.isEmpty()) {
                return null
            }

            var fallbackId: String? = null
            var rearCameraId: String? = null

            for (id in idList) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                        rearCameraId = id
                        break
                    } else if (fallbackId == null) {
                        fallbackId = id
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[CameraTestHarness] Could not inspect characteristics for camera $id", e)
                }
            }

            val chosen = rearCameraId ?: fallbackId ?: idList[0]
            Log.i(TAG, "[CameraTestHarness] Selected camera ID: $chosen")
            chosen
        } catch (e: Exception) {
            Log.e(TAG, "[CameraTestHarness] Error querying camera IDs", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSelectedCamera(cameraManager: CameraManager, cameraId: String) {
        _harnessState.value = HarnessState.CAMERA_OPENING
        _serviceStatus.value = "Opening camera: $cameraId"
        Log.i(TAG, "[CameraTestHarness] Camera opening: $cameraId")

        val handler = cameraHandler
        if (handler == null) {
            Log.e(TAG, "[CameraTestHarness] Camera handler is null")
            _harnessState.value = HarnessState.ERROR
            stopSimulation()
            return
        }

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    synchronized(cameraLock) {
                        if (!isSimulationRequested) {
                            Log.i(TAG, "[CameraTestHarness] Stop requested while opening; closing device")
                            device.close()
                            return
                        }
                        cameraDevice = device
                    }
                    Log.i(TAG, "[CameraTestHarness] Camera opened")
                    _harnessState.value = HarnessState.CAMERA_OPEN
                    _serviceStatus.value = "Camera opened ($cameraId)"
                    val rep = ExperimentLogger.repetition.value
                    ExperimentLogger.recordEvent(
                        userAction = if (isAutomatedSession) "AUTOMATED_TRIGGER_FIRED" else "NONE",
                        cameraEvent = "CAMERA_OPENED",
                        cameraId = cameraId,
                        cameraPermission = "GRANTED",
                        foregroundServiceActive = true,
                        foregroundServiceType = "camera",
                        sessionState = "CAMERA_OPEN",
                        cameraAvailability = "UNAVAILABLE",
                        recentUserInteraction = !isAutomatedSession,
                        notes = if (isAutomatedSession) "rep=$rep;trigger=countdown_timer;Camera opened by automated trigger" else "rep=$rep;trigger=user_start"
                    )
                    createCaptureSession(device, cameraManager, cameraId)
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "[CameraTestHarness] Camera disconnected")
                    synchronized(cameraLock) {
                        try {
                            device.close()
                        } catch (_: Exception) {}
                        if (cameraDevice == device) {
                            cameraDevice = null
                        }
                    }
                    _harnessState.value = HarnessState.ERROR
                    _serviceStatus.value = "Camera disconnected"
                    ExperimentLogger.recordEvent(
                        cameraEvent = "CAMERA_DISCONNECTED",
                        cameraId = cameraId,
                        sessionState = "ERROR",
                        cameraAvailability = "AVAILABLE",
                        notes = "Camera device disconnected unexpectedly"
                    )
                    stopSimulation()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "[CameraTestHarness] Camera error: $error")
                    synchronized(cameraLock) {
                        try {
                            device.close()
                        } catch (_: Exception) {}
                        if (cameraDevice == device) {
                            cameraDevice = null
                        }
                    }
                    _harnessState.value = HarnessState.ERROR
                    _serviceStatus.value = "Camera error: $error"
                    ExperimentLogger.recordEvent(
                        cameraEvent = "CAMERA_ERROR",
                        cameraId = cameraId,
                        sessionState = "ERROR",
                        notes = "Camera error code: $error"
                    )
                    stopSimulation()
                }

                override fun onClosed(device: CameraDevice) {
                    Log.i(TAG, "[CameraTestHarness] Camera closed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "[CameraTestHarness] Failed to call openCamera()", e)
            _harnessState.value = HarnessState.ERROR
            _serviceStatus.value = "Failed to open camera: ${e.message}"
            stopSimulation()
        }
    }

    private fun createCaptureSession(
        device: CameraDevice,
        cameraManager: CameraManager,
        cameraId: String
    ) {
        val handler = cameraHandler
        if (handler == null) {
            Log.e(TAG, "[CameraTestHarness] Camera handler unavailable for session creation")
            stopSimulation()
            return
        }

        try {
            // Find minimal resolution for non-persistent ImageReader target
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = streamMap?.getOutputSizes(ImageFormat.YUV_420_888)
                ?: streamMap?.getOutputSizes(ImageFormat.JPEG)
            val chosenSize = outputSizes?.minByOrNull { it.width * it.height } ?: Size(640, 480)

            Log.i(TAG, "[CameraTestHarness] Using minimal stream size: ${chosenSize.width}x${chosenSize.height}")

            val reader = ImageReader.newInstance(
                chosenSize.width,
                chosenSize.height,
                ImageFormat.YUV_420_888,
                2
            )

            // Immediately discard frames to preserve memory while maintaining active camera pipeline
            reader.setOnImageAvailableListener({ r ->
                try {
                    r.acquireLatestImage()?.close()
                } catch (_: Exception) {}
            }, handler)

            synchronized(cameraLock) {
                imageReader = reader
            }

            Log.i(TAG, "[CameraTestHarness] Capture session creation")
            _serviceStatus.value = "Creating capture session..."

            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    synchronized(cameraLock) {
                        if (!isSimulationRequested || cameraDevice == null) {
                            Log.i(TAG, "[CameraTestHarness] Session configured after stop requested; closing")
                            try { session.close() } catch (_: Exception) {}
                            return
                        }
                        captureSession = session
                    }

                    Log.i(TAG, "[CameraTestHarness] Capture session active")
                    _harnessState.value = HarnessState.CAPTURE_SESSION_ACTIVE
                    _serviceStatus.value = "Capture session active. Starting repeating request..."

                    // Start repeating preview request to keep camera hardware streaming
                    try {
                        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(reader.surface)
                        }
                        session.setRepeatingRequest(requestBuilder.build(), null, handler)

                        Log.i(TAG, "[CameraTestHarness] Simulation running")
                        Log.i(TAG, "[CameraTestHarness] Foreground service running")
                        _harnessState.value = HarnessState.RUNNING
                        _serviceStatus.value = "Simulation RUNNING (Camera: $cameraId active)"
                        updateNotification("Camera simulation active ($cameraId) - running")
                        val rep = ExperimentLogger.repetition.value
                        ExperimentLogger.recordEvent(
                            cameraEvent = "CAPTURE_SESSION_STARTED",
                            cameraId = cameraId,
                            cameraPermission = "GRANTED",
                            foregroundServiceActive = true,
                            foregroundServiceType = "camera",
                            sessionState = "RUNNING",
                            cameraAvailability = "UNAVAILABLE",
                            recentUserInteraction = !isAutomatedSession,
                            notes = if (isAutomatedSession) "rep=$rep;trigger=countdown_timer;Capture session streaming" else "rep=$rep;trigger=user_start"
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "[CameraTestHarness] Failed to start repeating request", e)
                        _harnessState.value = HarnessState.ERROR
                        _serviceStatus.value = "Repeating request failed: ${e.message}"
                        stopSimulation()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "[CameraTestHarness] Capture session configuration failed")
                    _harnessState.value = HarnessState.ERROR
                    _serviceStatus.value = "Capture session configuration failed"
                    stopSimulation()
                }

                override fun onClosed(session: CameraCaptureSession) {
                    Log.i(TAG, "[CameraTestHarness] Capture session closed")
                }
            }

            val targetSurface = reader.surface
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val executor = Executor { command -> handler.post(command) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(targetSurface)),
                    executor,
                    sessionCallback
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(targetSurface), sessionCallback, handler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[CameraTestHarness] Error creating capture session", e)
            _harnessState.value = HarnessState.ERROR
            _serviceStatus.value = "Session creation error: ${e.message}"
            stopSimulation()
        }
    }

    private fun stopSimulation() {
        val prevCameraId: String
        synchronized(cameraLock) {
            if (!isSimulationRequested && !isSessionActive) {
                Log.d(TAG, "[CameraTestHarness] Simulation already stopped or inactive; skipping stop")
                return
            }
            if (isStopping) {
                Log.d(TAG, "[CameraTestHarness] Stop already in progress; skipping duplicate stop")
                return
            }
            isStopping = true
            isSimulationRequested = false
            pendingTriggerRunnable?.let {
                cameraHandler?.removeCallbacks(it)
                pendingTriggerRunnable = null
            }
            isAutomatedTriggerArmed = false
            isAutomatedSession = false
            prevCameraId = selectedCameraId ?: "NONE"
            selectedCameraId = null

            _harnessState.value = HarnessState.STOPPING
            _serviceStatus.value = "Stopping simulation..."
            ExperimentLogger.recordEvent(
                userAction = "CAMERA_STOP_REQUESTED",
                cameraEvent = "CAMERA_STOP_REQUESTED",
                cameraId = prevCameraId,
                sessionState = "STOPPING"
            )

            // 1. Stop repeating capture requests
            try {
                captureSession?.stopRepeating()
            } catch (e: Exception) {
                Log.w(TAG, "[CameraTestHarness] Error stopping repeating request", e)
            }

            // 2. Close capture session
            try {
                captureSession?.close()
            } catch (e: Exception) {
                Log.w(TAG, "[CameraTestHarness] Error closing capture session", e)
            }
            captureSession = null

            // 3. Close CameraDevice
            try {
                cameraDevice?.close()
                Log.i(TAG, "[CameraTestHarness] Camera released")
            } catch (e: Exception) {
                Log.w(TAG, "[CameraTestHarness] Error closing camera device", e)
            }
            cameraDevice = null

            // 4. Close ImageReader
            try {
                imageReader?.close()
            } catch (e: Exception) {
                Log.w(TAG, "[CameraTestHarness] Error closing image reader", e)
            }
            imageReader = null

            // 5. Terminate camera background thread
            stopCameraThread()

            // 6. Stop foreground mode
            _isRunning.value = false
            if (_harnessState.value != HarnessState.ERROR) {
                _harnessState.value = HarnessState.IDLE
                _serviceStatus.value = "Simulation IDLE (Camera released)"
            }

            isSessionActive = false
            isStopping = false

            ExperimentLogger.recordEvent(
                cameraEvent = "CAMERA_CLOSED",
                cameraId = prevCameraId,
                sessionState = "IDLE",
                cameraAvailability = "AVAILABLE",
                foregroundServiceActive = false
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        Log.i(TAG, "[CameraTestHarness] Foreground service stopped")
    }

    private fun startCameraThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("CameraTestHarnessThread").apply {
                start()
                cameraHandler = Handler(looper)
            }
        }
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(300)
        } catch (_: InterruptedException) {}
        cameraThread = null
        cameraHandler = null
    }

    override fun onDestroy() {
        super.onDestroy()
        synchronized(cameraLock) {
            pendingTriggerRunnable?.let {
                cameraHandler?.removeCallbacks(it)
                pendingTriggerRunnable = null
            }
            isAutomatedTriggerArmed = false
            isAutomatedSession = false
            if (isSessionActive || isSimulationRequested) {
                stopSimulation()
            }
        }
        Log.i(TAG, "[CameraTestHarness] Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for CameraGuard Test Harness foreground simulation"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Test Harness (Simulator)")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(contentText: String) {
        try {
            val notification = buildNotification(contentText)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "[CameraTestHarness] Failed to update notification", e)
        }
    }
}
