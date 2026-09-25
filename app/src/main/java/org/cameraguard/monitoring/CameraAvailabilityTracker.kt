package org.cameraguard.monitoring

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import org.cameraguard.data.model.CameraAccessEvent
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.data.model.ScreenInteractivityState
import org.cameraguard.monitoring.detection.CameraRuleEvaluator
import org.cameraguard.monitoring.detection.hybrid.HybridCameraEvaluator
import org.cameraguard.monitoring.telemetry.ContextualInferenceEngine
import org.cameraguard.monitoring.telemetry.InferredPackageContext
import org.cameraguard.monitoring.telemetry.ScreenStateTracker

class CameraAvailabilityTracker(
    private val context: Context? = null,
    private val cameraMonitor: CameraMonitor? = context?.let { CameraMonitor.getInstance(it) },
    private val screenTracker: ScreenStateTracker? = context?.let { ScreenStateTracker(it) },
    val inferenceEngine: ContextualInferenceEngine? = context?.let { ContextualInferenceEngine(it) },
    val ruleEvaluator: CameraRuleEvaluator = CameraRuleEvaluator(),
    val hybridEvaluator: HybridCameraEvaluator = HybridCameraEvaluator(ruleEvaluator),
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    companion object {
        private const val TAG = "CameraGuard"
        const val DEDUPLICATION_WINDOW_MS = 3000L
        const val FALLBACK_POLL_INTERVAL_MS = 1000L
    }

    private val cameraManager: CameraManager? =
        context?.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val handler: Handler? by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }

    @Volatile
    var isRegistered: Boolean = false
        internal set

    @Volatile
    var lastHandledUsageTimestamp: Long = 0L

    @Volatile
    var lastAvailabilityTransitionTime: Long = 0L

    @Volatile
    var lastAvailabilityPackage: String? = null

    @Volatile
    var lastFallbackEventTimestamp: Long = 0L

    @Volatile
    var lastFallbackPackage: String? = null

    private var fallbackJob: Job? = null

    /**
     * In-memory per-camera availability state cache.
     * Maps cameraId -> last observed RawCameraEventType.
     */
    private val cameraStateMap = ConcurrentHashMap<String, RawCameraEventType>()

    /**
     * In-memory active camera-session owners.
     * Maps cameraId -> InferredPackageContext of the application that opened the camera session.
     */
    private val activeCameraSessionOwners = ConcurrentHashMap<String, InferredPackageContext>()

    /**
     * Returns the cached availability state for [cameraId], or null if not yet observed.
     */
    fun getCachedState(cameraId: String): RawCameraEventType? = cameraStateMap[cameraId]

    /**
     * Returns the active session owner for [cameraId], or null if none.
     */
    fun getActiveSessionOwner(cameraId: String): InferredPackageContext? = activeCameraSessionOwners[cameraId]

    /**
     * Clears all cached per-camera availability states and active session owners.
     */
    fun resetStateCache() {
        cameraStateMap.clear()
        activeCameraSessionOwners.clear()
    }

    /**
     * Listener invoked immediately whenever a new event is recorded.
     * Useful for triggering background notifications.
     */
    var onEventDetected: ((CameraAccessEvent) -> Unit)? = null

    val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            logD("onCameraUnavailable: cameraId=$cameraId")
            onCameraAvailabilityUpdate(
                cameraId = cameraId,
                rawType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE
            )
        }

        override fun onCameraAvailable(cameraId: String) {
            logD("onCameraAvailable: cameraId=$cameraId")
            onCameraAvailabilityUpdate(
                cameraId = cameraId,
                rawType = RawCameraEventType.CAMERA_BECAME_AVAILABLE
            )
        }

        override fun onPhysicalCameraUnavailable(cameraId: String, physicalCameraId: String) {
            logD("onPhysicalCameraUnavailable: cameraId=$cameraId, physicalCameraId=$physicalCameraId")
        }

        override fun onPhysicalCameraAvailable(cameraId: String, physicalCameraId: String) {
            logD("onPhysicalCameraAvailable: cameraId=$cameraId, physicalCameraId=$physicalCameraId")
        }

        override fun onCameraAccessPrioritiesChanged() {
            logD("onCameraAccessPrioritiesChanged")
        }
    }

    /**
     * Processes an incoming camera availability callback for a given [cameraId].
     *
     * 1. The first callback received for each cameraId establishes the baseline state
     *    and does NOT create a CameraAccessEvent.
     * 2. If a subsequent callback has the same state as the cached state, it is ignored.
     * 3. If a subsequent callback differs from the cached state, it is treated as a genuine
     *    observed availability transition, invoking [handleCameraTransition].
     */
    internal fun onCameraAvailabilityUpdate(
        cameraId: String,
        rawType: RawCameraEventType,
        currentTime: Long = System.currentTimeMillis()
    ) {
        val previousState = cameraStateMap.put(cameraId, rawType)
        if (previousState == null) {
            logD("Baseline established for cameraId=$cameraId: state=$rawType (suppressing event)")
            return
        }
        if (previousState == rawType) {
            logD("Duplicate availability state ignored for cameraId=$cameraId: state=$rawType")
            return
        }
        logD("Genuine transition for cameraId=$cameraId: $previousState -> $rawType")
        handleCameraTransition(
            cameraId = cameraId,
            rawType = rawType,
            currentTime = currentTime
        )
    }

    fun startTracking() {
        if (isRegistered || cameraManager == null) {
            logD("startTracking skipped: isRegistered=$isRegistered, cameraManagerIsNull=${cameraManager == null}")
            return
        }
        cameraStateMap.clear()
        activeCameraSessionOwners.clear()
        lastHandledUsageTimestamp = System.currentTimeMillis()
        lastAvailabilityTransitionTime = 0L
        lastAvailabilityPackage = null
        lastFallbackEventTimestamp = 0L
        lastFallbackPackage = null

        try {
            val cameraIds = try {
                cameraManager.cameraIdList.joinToString(", ")
            } catch (e: Exception) {
                "Error retrieving cameraIdList: ${e.message}"
            }
            logD("Available camera IDs on device: [$cameraIds]")

            cameraManager.registerAvailabilityCallback(availabilityCallback, handler)
            isRegistered = true
            cameraMonitor?.startMonitoring()
            logD("registerAvailabilityCallback: SUCCESS")

            startUsageStatsFallbackLoop()
        } catch (e: Exception) {
            isRegistered = false
            logE("registerAvailabilityCallback: FAILED", e)
        }
    }

    fun stopTracking() {
        stopUsageStatsFallbackLoop()
        cameraStateMap.clear()
        activeCameraSessionOwners.clear()
        if (!isRegistered || cameraManager == null) return
        try {
            cameraManager.unregisterAvailabilityCallback(availabilityCallback)
            isRegistered = false
            cameraMonitor?.stopMonitoring()
            logD("unregisterAvailabilityCallback: SUCCESS")
        } catch (e: Exception) {
            logE("unregisterAvailabilityCallback: FAILED", e)
        }
    }

    private fun startUsageStatsFallbackLoop() {
        fallbackJob?.cancel()
        fallbackJob = coroutineScope.launch {
            while (isActive && isRegistered) {
                try {
                    checkUsageStatsFallback()
                } catch (e: Exception) {
                    logE("Error during checkUsageStatsFallback", e)
                }
                delay(FALLBACK_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopUsageStatsFallbackLoop() {
        fallbackJob?.cancel()
        fallbackJob = null
    }

    /**
     * Checks for foreground camera applications via UsageStats when CameraAvailabilityCallback
     * does not produce availability transitions (e.g. stock Camera on certain OEM devices).
     *
     * Returns true if a fallback event was created, false otherwise.
     */
    internal fun checkUsageStatsFallback(currentTime: Long = System.currentTimeMillis()): Boolean {
        if (!isRegistered) return false

        val candidate = inferenceEngine?.findForegroundCameraActivity(currentTime) ?: return false

        // 1. Deduplication: Check if we already handled this specific UsageStats activity resumption
        if (candidate.timestamp <= lastHandledUsageTimestamp) {
            return false
        }

        // 2. Duplicate Prevention: Check if CameraAvailabilityCallback already handled this camera access
        val availabilityDelta = abs(currentTime - lastAvailabilityTransitionTime)
        val isHardwareCameraUnavailable = cameraStateMap.values.any { it == RawCameraEventType.CAMERA_BECAME_UNAVAILABLE }

        if (availabilityDelta <= DEDUPLICATION_WINDOW_MS || isHardwareCameraUnavailable) {
            logD("UsageStats fallback suppressed: availability callback already active (delta=${availabilityDelta}ms, hwUnavailable=$isHardwareCameraUnavailable)")
            lastHandledUsageTimestamp = candidate.timestamp
            return false
        }

        // 3. Mark as handled to prevent duplicate triggers for the same launch
        lastHandledUsageTimestamp = candidate.timestamp
        lastFallbackEventTimestamp = currentTime
        lastFallbackPackage = candidate.packageName

        // 4. Create unified CameraAccessEvent through hybrid evaluator
        val screenState = screenTracker?.currentScreenState()
            ?: ScreenInteractivityState.SCREEN_ON_UNLOCKED

        val inferredContext = InferredPackageContext(
            packageName = candidate.packageName,
            confidence = InferenceConfidence.HIGH,
            method = InferenceMethod.USAGE_STATS_FALLBACK,
            hasCameraPermission = candidate.hasCameraPermission,
            deltaFromEventMs = 0L
        )

        val isKnownCamera = candidate.packageName.let {
            inferenceEngine.isCameraApplication(it)
        }

        val hybridResult = hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = screenState,
            inferredContext = inferredContext,
            cameraId = null,
            isKnownCameraApp = isKnownCamera
        )

        val detectionLatency = (currentTime - candidate.timestamp).coerceAtLeast(0L)

        val event = CameraAccessEvent(
            timestamp = candidate.timestamp,
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            cameraId = null,
            screenState = screenState,
            inferredPackageName = candidate.packageName,
            packageInferenceConfidence = InferenceConfidence.HIGH,
            inferenceMethod = InferenceMethod.USAGE_STATS_FALLBACK,
            candidateHasCameraPermission = candidate.hasCameraPermission,
            classification = hybridResult.finalClassification,
            classificationExplanation = hybridResult.explanation,
            detectionLatencyMs = detectionLatency,
            isSynthetic = false,
            tierUsed = hybridResult.tierUsed,
            deterministicResult = hybridResult.deterministicResult,
            mlResult = hybridResult.mlResult,
            mlInvoked = hybridResult.mlInvoked
        )

        logD("UsageStats fallback created unified CameraAccessEvent: package=${candidate.packageName}, class=${hybridResult.finalClassification}")
        cameraMonitor?.recordEvent(event)
        onEventDetected?.invoke(event)
        return true
    }

    internal fun handleCameraTransition(
        cameraId: String,
        rawType: RawCameraEventType,
        currentTime: Long = System.currentTimeMillis()
    ) {
        val startCaptureTime = currentTime

        // Bidirectional deduplication: if UsageStats fallback just created an event for this camera access, suppress duplicate
        val fallbackDelta = abs(startCaptureTime - lastFallbackEventTimestamp)
        if (rawType == RawCameraEventType.CAMERA_BECAME_UNAVAILABLE && fallbackDelta <= DEDUPLICATION_WINDOW_MS) {
            logD("handleCameraTransition suppressed: UsageStats fallback already created event (delta=${fallbackDelta}ms)")
            return
        }

        // 1. Raw Telemetry: screen interactivity
        val screenState = screenTracker?.currentScreenState()
            ?: ScreenInteractivityState.SCREEN_ON_UNLOCKED

        // 2. Inferred application context with session-owner attribution on closure
        val inferredContext = if (rawType == RawCameraEventType.CAMERA_BECAME_AVAILABLE) {
            val sessionOwner = activeCameraSessionOwners.remove(cameraId)
            if (sessionOwner != null && sessionOwner.packageName != null) {
                logD("Attributing closure of cameraId=$cameraId to active session owner: ${sessionOwner.packageName}")
                sessionOwner.copy(deltaFromEventMs = 0L)
            } else {
                logD("No active session owner for cameraId=$cameraId closure; leaving inferred package as null")
                InferredPackageContext(
                    packageName = null,
                    confidence = InferenceConfidence.NONE,
                    method = InferenceMethod.NONE,
                    hasCameraPermission = null,
                    deltaFromEventMs = null
                )
            }
        } else {
            val inferred = inferenceEngine?.inferForegroundPackage(startCaptureTime)
                ?: InferredPackageContext(
                    packageName = null,
                    confidence = InferenceConfidence.NONE,
                    method = InferenceMethod.NONE,
                    hasCameraPermission = null,
                    deltaFromEventMs = null
                )
            if (inferred.packageName != null) {
                activeCameraSessionOwners[cameraId] = inferred
                logD("Stored active session owner for cameraId=$cameraId: ${inferred.packageName}")
            } else {
                activeCameraSessionOwners.remove(cameraId)
                logD("No package inferred for cameraId=$cameraId; cleared session owner")
            }
            lastAvailabilityTransitionTime = startCaptureTime
            lastAvailabilityPackage = inferred.packageName
            inferred
        }

        // 3. Classification via Hybrid Evaluator
        val isKnownCamera = inferredContext.packageName != null &&
                (inferenceEngine?.isCameraApplication(inferredContext.packageName) == true)

        val hybridResult = hybridEvaluator.evaluate(
            rawEventType = rawType,
            screenState = screenState,
            inferredContext = inferredContext,
            cameraId = cameraId,
            isKnownCameraApp = isKnownCamera
        )

        val detectionLatency = (System.currentTimeMillis() - startCaptureTime).coerceAtLeast(0L)

        val event = CameraAccessEvent(
            timestamp = startCaptureTime,
            rawEventType = rawType,
            cameraId = cameraId,
            screenState = screenState,
            inferredPackageName = inferredContext.packageName,
            packageInferenceConfidence = inferredContext.confidence,
            inferenceMethod = inferredContext.method,
            candidateHasCameraPermission = inferredContext.hasCameraPermission,
            classification = hybridResult.finalClassification,
            classificationExplanation = hybridResult.explanation,
            detectionLatencyMs = detectionLatency,
            isSynthetic = false,
            tierUsed = hybridResult.tierUsed,
            deterministicResult = hybridResult.deterministicResult,
            mlResult = hybridResult.mlResult,
            mlInvoked = hybridResult.mlInvoked
        )

        cameraMonitor?.recordEvent(event)
        onEventDetected?.invoke(event)
    }

    private fun logD(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: Throwable) {
            // Android Log not mocked in local JVM unit tests
        }
    }

    private fun logE(message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                Log.e(TAG, message, throwable)
            } else {
                Log.e(TAG, message)
            }
        } catch (_: Throwable) {
            // Android Log not mocked in local JVM unit tests
        }
    }
}

