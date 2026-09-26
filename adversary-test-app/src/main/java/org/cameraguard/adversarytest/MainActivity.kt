package org.cameraguard.adversarytest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : Activity() {

    companion object {
        const val TAG = "AdversaryTestApp"
        const val EXTRA_ACTION = "action"
        const val EXTRA_HOLD_DURATION_MS = "hold_duration_ms"
        const val EXTRA_DELAY_MS = "delay_ms"
        const val EXTRA_BURST_COUNT = "burst_count"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var activeCameraDevice: CameraDevice? = null
    private lateinit var statusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val thread = HandlerThread("AdversaryActivityCameraThread").apply { start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)

        statusTextView = TextView(this).apply {
            textSize = 16f
            setPadding(32, 64, 32, 32)
            text = "CameraGuard Adversary Test Application\nStatus: Initializing..."
        }
        setContentView(statusTextView)

        checkAndRequestPermissions()

        val actionExtra = intent?.getStringExtra(EXTRA_ACTION)
        if (actionExtra != null) {
            handleActionIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleActionIntent(it) }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun handleActionIntent(intent: Intent) {
        val actionString = intent.getStringExtra(EXTRA_ACTION) ?: return
        val scenario = AdversaryScenario.fromId(actionString)
        if (scenario == null) {
            Log.e(TAG, "Unknown action received: $actionString")
            updateStatus("Unknown scenario: $actionString")
            return
        }

        val holdDurationMs = intent.getLongExtra(EXTRA_HOLD_DURATION_MS, 2000L)
        val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 500L)
        val burstCount = intent.getIntExtra(EXTRA_BURST_COUNT, 3)

        Log.i(TAG, "[ACTION_START] scenario=${scenario.id} description=\"${scenario.description}\"")
        updateStatus("Executing ${scenario.id}: ${scenario.description}")

        activityScope.launch {
            when (scenario) {
                AdversaryScenario.A1_FOREGROUND -> executeA1Foreground(holdDurationMs, delayMs)
                AdversaryScenario.A2_BACKGROUND -> executeA2Background(holdDurationMs, delayMs)
                AdversaryScenario.A3_POST_FOREGROUND -> executeA3PostForeground(holdDurationMs, delayMs)
                AdversaryScenario.A4_SERVICE -> executeA4Service(holdDurationMs, delayMs)
                AdversaryScenario.A5_SCREEN_LOCKED -> executeA5ScreenLocked(holdDurationMs, delayMs)
                AdversaryScenario.A6_RAPID -> executeA6RapidBurst(burstCount, holdDurationMs)
            }
            Log.i(TAG, "[ACTION_END] scenario=${scenario.id}")
            updateStatus("Completed ${scenario.id}")
        }
    }

    private suspend fun executeA1Foreground(holdDurationMs: Long, initialDelayMs: Long) {
        if (initialDelayMs > 0) delay(initialDelayMs)
        val result = attemptCameraAccess("A1", holdDurationMs)
        updateStatus("A1 Result: $result")
    }

    private suspend fun executeA2Background(holdDurationMs: Long, initialDelayMs: Long) {
        if (initialDelayMs > 0) delay(initialDelayMs)
        Log.i(TAG, "[SCENARIO_PREP] scenario=A2 moving to background")
        moveTaskToBack(true)
        // Wait 2000ms so the process is definitively in background
        delay(2000L)
        Log.i(TAG, "[SCENARIO_EXEC] scenario=A2 attempting background access")
        val result = attemptCameraAccess("A2", holdDurationMs)
        updateStatus("A2 Result: $result")
    }

    private suspend fun executeA3PostForeground(holdDurationMs: Long, initialDelayMs: Long) {
        if (initialDelayMs > 0) delay(initialDelayMs)
        Log.i(TAG, "[SCENARIO_PREP] scenario=A3 moving to background immediately before access")
        moveTaskToBack(true)
        // Short transitional delay (150ms) to test post-foreground boundary
        delay(150L)
        Log.i(TAG, "[SCENARIO_EXEC] scenario=A3 attempting immediate post-foreground access")
        val result = attemptCameraAccess("A3", holdDurationMs)
        updateStatus("A3 Result: $result")
    }

    private fun executeA4Service(holdDurationMs: Long, initialDelayMs: Long) {
        Log.i(TAG, "[SCENARIO_EXEC] scenario=A4 launching AdversaryCameraService")
        val serviceIntent = Intent(this, AdversaryCameraService::class.java).apply {
            action = AdversaryCameraService.ACTION_START_A4
            putExtra(AdversaryCameraService.EXTRA_HOLD_DURATION_MS, holdDurationMs)
            putExtra(AdversaryCameraService.EXTRA_DELAY_BEFORE_OPEN_MS, initialDelayMs)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        moveTaskToBack(true)
        updateStatus("A4 Service started in background")
    }

    private suspend fun executeA5ScreenLocked(holdDurationMs: Long, initialDelayMs: Long) {
        val waitTime = if (initialDelayMs > 0) initialDelayMs else 4000L
        Log.i(TAG, "[SCENARIO_PREP] scenario=A5 waiting ${waitTime}ms for lockscreen")
        updateStatus("A5: Lock device now! Waiting ${waitTime}ms...")
        delay(waitTime)
        Log.i(TAG, "[SCENARIO_EXEC] scenario=A5 attempting access while screen is locked")
        val result = attemptCameraAccess("A5", holdDurationMs)
        updateStatus("A5 Result: $result")
    }

    private suspend fun executeA6RapidBurst(burstCount: Int, holdDurationMs: Long) {
        Log.i(TAG, "[SCENARIO_EXEC] scenario=A6 starting burst count=$burstCount")
        val burstHold = if (holdDurationMs > 1000L) 600L else holdDurationMs
        for (i in 1..burstCount) {
            val cycleTag = "A6_burst_$i"
            Log.i(TAG, "[SCENARIO_BURST_CYCLE] cycle=$i of $burstCount")
            attemptCameraAccess(cycleTag, burstHold)
            delay(500L) // Minimum turnaround latency between cycles
        }
        updateStatus("A6 Rapid burst finished ($burstCount cycles)")
    }

    @SuppressLint("MissingPermission")
    private suspend fun attemptCameraAccess(scenarioId: String, holdDurationMs: Long): String {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "FAILED_NO_CAMERA_MANAGER".also {
                Log.e(TAG, "[ACCESS_RESULT] scenario=$scenarioId groundTruth=FAILED detail=CameraManager unavailable")
            }

        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull() ?: "0"
        } catch (e: Exception) {
            "0"
        }

        Log.i(TAG, "[ACCESS_ATTEMPT] scenario=$scenarioId cameraId=$cameraId component=MainActivity")

        return suspendCoroutine { continuation ->
            var resumed = false
            fun safeResume(result: String) {
                if (!resumed) {
                    resumed = true
                    continuation.resume(result)
                }
            }

            try {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        activeCameraDevice = camera
                        Log.i(TAG, "[ACCESS_RESULT] scenario=$scenarioId groundTruth=SUCCESS detail=CameraDevice opened successfully")
                        activityScope.launch {
                            delay(holdDurationMs)
                            Log.i(TAG, "[ACCESS_CLOSURE] scenario=$scenarioId durationMs=$holdDurationMs")
                            closeCamera()
                            safeResume("SUCCESS")
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.i(TAG, "[ACCESS_RESULT] scenario=$scenarioId groundTruth=FAILED detail=Camera disconnected")
                        camera.close()
                        safeResume("DISCONNECTED")
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
                        Log.e(TAG, "[ACCESS_RESULT] scenario=$scenarioId groundTruth=$groundTruth detail=$errorDetail")
                        camera.close()
                        safeResume(groundTruth)
                    }
                }, cameraHandler)
            } catch (se: SecurityException) {
                Log.e(TAG, "[ACCESS_RESULT] scenario=$scenarioId groundTruth=BLOCKED_BY_PLATFORM detail=SecurityException: ${se.message}")
                safeResume("BLOCKED_BY_PLATFORM")
            } catch (cae: CameraAccessException) {
                val groundTruth = if (cae.reason == CameraAccessException.CAMERA_DISABLED) "BLOCKED_BY_PLATFORM" else "FAILED"
                Log.e(TAG, "[ACCESS_RESULT] scenario=$scenarioId groundTruth=$groundTruth detail=CameraAccessException(reason=${cae.reason}): ${cae.message}")
                safeResume(groundTruth)
            } catch (e: Exception) {
                Log.e(TAG, "[ACCESS_RESULT] scenario=$scenarioId groundTruth=FAILED detail=Exception: ${e.message}")
                safeResume("FAILED")
            }
        }
    }

    private fun closeCamera() {
        try {
            activeCameraDevice?.close()
        } catch (_: Exception) {}
        activeCameraDevice = null
    }

    private fun updateStatus(msg: String) {
        runOnUiThread {
            statusTextView.text = "CameraGuard Adversary Test Application\n$msg"
        }
    }

    override fun onDestroy() {
        closeCamera()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        super.onDestroy()
    }
}
