package org.cameraguard.adversarytest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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

    // Controller
    private val controller = AdversarialTestController(activityScope)

    // UI elements
    private lateinit var statusBadge: TextView
    private lateinit var telemetryTextView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var permissionCard: LinearLayout
    private lateinit var durationButton5s: Button
    private lateinit var durationButton10s: Button
    private lateinit var durationButton15s: Button
    private lateinit var durationButton30s: Button
    private lateinit var cameraInfoTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val thread = HandlerThread("AdversaryActivityCameraThread").apply { start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)

        setupControllerCallbacks()
        setContentView(buildJuryUI())
        resolveAvailableCameras()
        checkPermissionsAndUpdateUI()

        val actionExtra = intent?.getStringExtra(EXTRA_ACTION)
        if (actionExtra != null) {
            handleActionIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndUpdateUI()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleActionIntent(it) }
    }

    private fun setupControllerCallbacks() {
        controller.onCloseCamera = {
            closeCamera()
        }

        controller.onStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    AdversarialTestController.TestState.CLOSED -> {
                        statusBadge.text = getString(R.string.status_closed)
                        statusBadge.setBackgroundColor(Color.parseColor("#757575"))
                        startButton.isEnabled = hasCameraPermission()
                        stopButton.isEnabled = false
                        telemetryTextView.text = "Session idle. Camera hardware released."
                    }
                    AdversarialTestController.TestState.ACTIVE -> {
                        statusBadge.text = getString(R.string.status_active)
                        statusBadge.setBackgroundColor(Color.parseColor("#2E7D32")) // Green
                        startButton.isEnabled = false
                        stopButton.isEnabled = true
                    }
                    AdversarialTestController.TestState.COMPLETE -> {
                        statusBadge.text = getString(R.string.status_complete)
                        statusBadge.setBackgroundColor(Color.parseColor("#1565C0")) // Blue
                        startButton.isEnabled = hasCameraPermission()
                        stopButton.isEnabled = false
                        telemetryTextView.text = "Test completed successfully (${controller.selectedDurationSeconds}s). Camera closed cleanly."
                    }
                    AdversarialTestController.TestState.NO_CAMERA_AVAILABLE -> {
                        statusBadge.text = getString(R.string.status_no_camera)
                        statusBadge.setBackgroundColor(Color.parseColor("#C62828"))
                        startButton.isEnabled = false
                        stopButton.isEnabled = false
                        telemetryTextView.text = "Error: No rear or hardware camera found on this device."
                    }
                    AdversarialTestController.TestState.ERROR -> {
                        statusBadge.text = "CAMERA ERROR"
                        statusBadge.setBackgroundColor(Color.parseColor("#C62828"))
                        startButton.isEnabled = hasCameraPermission()
                        stopButton.isEnabled = false
                        telemetryTextView.text = "Error: ${controller.lastErrorMessage ?: "Failed to acquire camera"}"
                    }
                }
            }
        }

        controller.onTick = { elapsed, remaining ->
            runOnUiThread {
                val elapsedStr = String.format("%02d:%02d", elapsed / 60, elapsed % 60)
                val remainingStr = String.format("%02d:%02d", remaining / 60, remaining % 60)
                telemetryTextView.text = "Elapsed: $elapsedStr  |  Remaining: $remainingStr\nActive Camera ID: ${controller.activeCameraId}"
            }
        }
    }

    private fun resolveAvailableCameras() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val descriptors = mutableListOf<CameraInfoDescriptor>()
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val isRear = (facing == CameraCharacteristics.LENS_FACING_BACK)
                descriptors.add(CameraInfoDescriptor(id, isRear))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying camera IDs: ${e.message}")
        }
        val targetId = controller.resolveTargetCameraId(descriptors)
        val facingText = if (targetId != null) "Rear Camera (Camera ID: $targetId)" else "No camera detected"
        cameraInfoTextView.text = "Target Sensor: $facingText\nCaller Package: $packageName"
    }

    private fun checkPermissionsAndUpdateUI() {
        val granted = hasCameraPermission()
        permissionCard.visibility = if (granted) View.GONE else View.VISIBLE
        if (controller.currentState != AdversarialTestController.TestState.ACTIVE) {
            startButton.isEnabled = granted
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            checkPermissionsAndUpdateUI()
        }
    }

    private fun startJuryCameraSession() {
        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }

        val targetId = controller.activeCameraId ?: run {
            resolveAvailableCameras()
            controller.activeCameraId
        }

        controller.startTest(targetId) { cameraId, onOpened, onError ->
            openCameraHardware(cameraId, onOpened, onError)
        }
    }

    private fun stopJuryCameraSession() {
        controller.stopTest(isCompleted = false) {
            closeCamera()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraHardware(
        cameraId: String,
        onOpened: () -> Unit,
        onError: (String) -> Unit
    ) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            onError("CameraManager not available")
            return
        }

        try {
            Log.i(TAG, "[JURY_DEMO_START] Opening Camera2 device cameraId=$cameraId")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    activeCameraDevice = camera
                    Log.i(TAG, "[JURY_DEMO_OPENED] CameraDevice opened successfully: cameraId=$cameraId")
                    onOpened()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "[JURY_DEMO_DISCONNECTED] Camera disconnected: cameraId=$cameraId")
                    camera.close()
                    activeCameraDevice = null
                    controller.stopTest(isCompleted = false)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val errorDetail = when (error) {
                        ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED (Blocked by platform policy)"
                        ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
                        ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
                        ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE"
                        ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE"
                        else -> "UNKNOWN_ERROR_$error"
                    }
                    Log.e(TAG, "[JURY_DEMO_ERROR] Camera error: $errorDetail")
                    camera.close()
                    activeCameraDevice = null
                    onError(errorDetail)
                }
            }, cameraHandler)
        } catch (se: SecurityException) {
            Log.e(TAG, "[JURY_DEMO_SECURITY] SecurityException opening camera: ${se.message}")
            onError("SecurityException: ${se.message}")
        } catch (cae: CameraAccessException) {
            Log.e(TAG, "[JURY_DEMO_ACCESS] CameraAccessException: ${cae.message}")
            onError("CameraAccessException: ${cae.message}")
        } catch (e: Exception) {
            Log.e(TAG, "[JURY_DEMO_FAIL] General error: ${e.message}")
            onError("Exception: ${e.message}")
        }
    }

    private fun closeCamera() {
        try {
            activeCameraDevice?.close()
        } catch (_: Exception) {}
        activeCameraDevice = null
        Log.i(TAG, "[JURY_DEMO_CLOSED] Camera closed cleanly")
    }

    private fun updateDurationSelection(seconds: Int) {
        controller.selectDuration(seconds)
        val selectedColor = Color.parseColor("#1565C0") // Blue
        val normalColor = Color.parseColor("#E0E0E0") // Gray

        durationButton5s.setBackgroundColor(if (seconds == 5) selectedColor else normalColor)
        durationButton5s.setTextColor(if (seconds == 5) Color.WHITE else Color.BLACK)

        durationButton10s.setBackgroundColor(if (seconds == 10) selectedColor else normalColor)
        durationButton10s.setTextColor(if (seconds == 10) Color.WHITE else Color.BLACK)

        durationButton15s.setBackgroundColor(if (seconds == 15) selectedColor else normalColor)
        durationButton15s.setTextColor(if (seconds == 15) Color.WHITE else Color.BLACK)

        durationButton30s.setBackgroundColor(if (seconds == 30) selectedColor else normalColor)
        durationButton30s.setTextColor(if (seconds == 30) Color.WHITE else Color.BLACK)
    }

    // =========================================================================
    // Programmatic Clean Material UI Layout
    // =========================================================================
    private fun buildJuryUI(): View {
        val rootScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#F5F5F7"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootScrollView.addView(container)

        // 1. App Header Title
        val titleText = TextView(this).apply {
            text = getString(R.string.ui_title)
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        container.addView(titleText)

        // 2. Subtitle / Purpose
        val purposeText = TextView(this).apply {
            text = getString(R.string.ui_purpose)
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 8, 0, 32)
        }
        container.addView(purposeText)

        // 3. Status Badge
        statusBadge = TextView(this).apply {
            text = getString(R.string.status_closed)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#757575"))
            setPadding(32, 24, 32, 24)
            val gd = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#757575"))
            }
            background = gd
        }
        container.addView(statusBadge)

        // Spacer
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 32) })

        // 4. Live Telemetry Box
        val telemetryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#E0E0E0"))
            }
        }
        telemetryTextView = TextView(this).apply {
            text = "Session idle. Camera hardware released."
            textSize = 15f
            setTextColor(Color.parseColor("#333333"))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        telemetryCard.addView(telemetryTextView)
        container.addView(telemetryCard)

        // Spacer
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 32) })

        // 5. Information Card
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#E0E0E0"))
            }
        }
        val infoTitle = TextView(this).apply {
            text = "Test Configuration"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(0, 0, 0, 16)
        }
        infoCard.addView(infoTitle)

        cameraInfoTextView = TextView(this).apply {
            text = "Target Sensor: Rear Camera\nCaller Package: $packageName"
            textSize = 14f
            setTextColor(Color.parseColor("#555555"))
            setLineSpacing(8f, 1f)
            setPadding(0, 0, 0, 24)
        }
        infoCard.addView(cameraInfoTextView)

        // Duration Label
        val durationLabel = TextView(this).apply {
            text = "Select Acquisition Duration:"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, 12)
        }
        infoCard.addView(durationLabel)

        // Duration Selector Buttons
        val durationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        durationButton5s = Button(this).apply {
            text = "5s"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { updateDurationSelection(5) }
        }
        durationButton10s = Button(this).apply {
            text = "10s"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { updateDurationSelection(10) }
        }
        durationButton15s = Button(this).apply {
            text = "15s"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { updateDurationSelection(15) }
        }
        durationButton30s = Button(this).apply {
            text = "30s"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { updateDurationSelection(30) }
        }
        durationRow.addView(durationButton5s)
        durationRow.addView(durationButton10s)
        durationRow.addView(durationButton15s)
        durationRow.addView(durationButton30s)
        infoCard.addView(durationRow)

        container.addView(infoCard)

        // Spacer
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 32) })

        // 6. Action Buttons
        startButton = Button(this).apply {
            text = getString(R.string.btn_start)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2E7D32")) // Emerald Green
                cornerRadius = 16f
            }
            setPadding(0, 28, 0, 28)
            setOnClickListener { startJuryCameraSession() }
        }
        container.addView(startButton)

        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 16) })

        stopButton = Button(this).apply {
            text = getString(R.string.btn_stop)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isEnabled = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#C62828")) // Red
                cornerRadius = 16f
            }
            setPadding(0, 28, 0, 28)
            setOnClickListener { stopJuryCameraSession() }
        }
        container.addView(stopButton)

        // Spacer
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 32) })

        // 7. Permission Warning Card (conditional)
        permissionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF3E0")) // Light Amber
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#FFE0B2"))
            }
        }
        val permWarning = TextView(this).apply {
            text = getString(R.string.permission_required)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E65100"))
            setPadding(0, 0, 0, 16)
        }
        val permButton = Button(this).apply {
            text = getString(R.string.btn_grant_perm)
            setOnClickListener { requestCameraPermission() }
        }
        permissionCard.addView(permWarning)
        permissionCard.addView(permButton)
        container.addView(permissionCard)

        updateDurationSelection(AdversarialTestController.DEFAULT_DURATION_SECONDS)
        return rootScrollView
    }

    // =========================================================================
    // Automated Research Scenario Handler (Preserves Phase 5 backwards-compat)
    // =========================================================================
    private fun handleActionIntent(intent: Intent) {
        val actionString = intent.getStringExtra(EXTRA_ACTION) ?: return
        val scenario = AdversaryScenario.fromId(actionString)
        if (scenario == null) {
            Log.e(TAG, "Unknown action received: $actionString")
            return
        }

        val holdDurationMs = intent.getLongExtra(EXTRA_HOLD_DURATION_MS, 2000L)
        val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 500L)
        val burstCount = intent.getIntExtra(EXTRA_BURST_COUNT, 3)

        Log.i(TAG, "[ACTION_START] scenario=${scenario.id} description=\"${scenario.description}\"")

        activityScope.launch {
            when (scenario) {
                AdversaryScenario.A1_FOREGROUND -> {
                    if (delayMs > 0) delay(delayMs)
                    attemptCameraAccessLegacy("A1", holdDurationMs)
                }
                AdversaryScenario.A2_BACKGROUND -> {
                    if (delayMs > 0) delay(delayMs)
                    moveTaskToBack(true)
                    delay(2000L)
                    attemptCameraAccessLegacy("A2", holdDurationMs)
                }
                AdversaryScenario.A3_POST_FOREGROUND -> {
                    if (delayMs > 0) delay(delayMs)
                    moveTaskToBack(true)
                    delay(150L)
                    attemptCameraAccessLegacy("A3", holdDurationMs)
                }
                AdversaryScenario.A4_SERVICE -> {
                    val serviceIntent = Intent(this@MainActivity, AdversaryCameraService::class.java).apply {
                        action = AdversaryCameraService.ACTION_START_A4
                        putExtra(AdversaryCameraService.EXTRA_HOLD_DURATION_MS, holdDurationMs)
                        putExtra(AdversaryCameraService.EXTRA_DELAY_BEFORE_OPEN_MS, delayMs)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    moveTaskToBack(true)
                }
                AdversaryScenario.A5_SCREEN_LOCKED -> {
                    val waitTime = if (delayMs > 0) delayMs else 4000L
                    delay(waitTime)
                    attemptCameraAccessLegacy("A5", holdDurationMs)
                }
                AdversaryScenario.A6_RAPID -> {
                    val burstHold = if (holdDurationMs > 1000L) 600L else holdDurationMs
                    for (i in 1..burstCount) {
                        attemptCameraAccessLegacy("A6_burst_$i", burstHold)
                        delay(500L)
                    }
                }
            }
            Log.i(TAG, "[ACTION_END] scenario=${scenario.id}")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun attemptCameraAccessLegacy(scenarioId: String, holdDurationMs: Long): String {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "FAILED_NO_CAMERA_MANAGER"

        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull() ?: "0"
        } catch (e: Exception) {
            "0"
        }

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
                        camera.close()
                        safeResume("DISCONNECTED")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        val isPolicyBlocked = (error == ERROR_CAMERA_DISABLED)
                        val groundTruth = if (isPolicyBlocked) "BLOCKED_BY_PLATFORM" else "FAILED"
                        camera.close()
                        safeResume(groundTruth)
                    }
                }, cameraHandler)
            } catch (se: SecurityException) {
                safeResume("BLOCKED_BY_PLATFORM")
            } catch (cae: CameraAccessException) {
                val groundTruth = if (cae.reason == CameraAccessException.CAMERA_DISABLED) "BLOCKED_BY_PLATFORM" else "FAILED"
                safeResume(groundTruth)
            } catch (e: Exception) {
                safeResume("FAILED")
            }
        }
    }

    override fun onDestroy() {
        controller.cleanup { closeCamera() }
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        super.onDestroy()
    }
}
