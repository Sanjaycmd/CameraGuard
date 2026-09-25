package org.cameratestharness.experiment

/**
 * Real-time production ML feature vector strictly restricted to the T0 activation window.
 * Structurally guarantees that no retrospective (T2), internal harness, or target label signals
 * can enter the production feature set.
 */
data class T0ProductionFeatureVector(
    // F01: Screen state code [ON_UNLOCKED, ON_LOCKED, OFF, UNKNOWN]
    val f01ScreenStateCode: String,

    // F02: Screen is interactive [TRUE, FALSE, UNKNOWN]
    val f02IsScreenInteractive: String,

    // F03: Device is locked [TRUE, FALSE, UNKNOWN]
    val f03IsDeviceLocked: String,

    // F04: Target application camera permission status [GRANTED, DENIED, UNVERIFIED, UNKNOWN]
    // In production CameraGuard, strictly UNVERIFIED due to Android sandbox boundaries.
    val f04HasCameraPermission: String,

    // F05: Inferred app matches known camera/video communication package [true, false]
    val f05IsKnownCameraApp: Boolean,

    // F06: Package attribution confidence rating [0..3: 0=NONE, 1=LOW, 2=MEDIUM, 3=HIGH]
    val f06PackageInferenceConfidence: Int,

    // F07: Heuristic inference method code [USAGE_STATS_ACTIVITY_RESUMED, USAGE_STATS_ACTIVITY_PAUSED, CAMERA_APP_HEURISTIC, NONE, UNKNOWN]
    val f07InferenceMethodCode: String,

    // F08: Time delta in ms from last app resume to camera trigger [-1.0 if unavailable]
    val f08DeltaResumedToTriggerMs: Double,

    // F09: Recent activity transition count in 30s lookback window [>= 0]
    val f09RecentActivityCount30s: Int,

    // F10: Camera hardware sensor ID ["0", "1", "UNKNOWN"]
    val f10CameraHardwareId: String,

    // F11: Lens facing is back camera [TRUE, FALSE, UNKNOWN]
    val f11IsBackCamera: String
) {
    /**
     * Converts to a map of feature name to raw feature value.
     */
    fun toFeatureMap(): Map<String, Any> {
        return mapOf(
            "f01_screen_state_code" to f01ScreenStateCode,
            "f02_is_screen_interactive" to f02IsScreenInteractive,
            "f03_is_device_locked" to f03IsDeviceLocked,
            "f04_has_camera_permission" to f04HasCameraPermission,
            "f05_is_known_camera_app" to f05IsKnownCameraApp,
            "f06_package_inference_confidence" to f06PackageInferenceConfidence,
            "f07_inference_method_code" to f07InferenceMethodCode,
            "f08_delta_resumed_to_trigger_ms" to f08DeltaResumedToTriggerMs,
            "f09_recent_activity_count_30s" to f09RecentActivityCount30s,
            "f10_camera_hardware_id" to f10CameraHardwareId,
            "f11_is_back_camera" to f11IsBackCamera
        )
    }

    /**
     * Emits CSV value strings for T0 features.
     */
    fun toCsvValues(): List<String> {
        return listOf(
            f01ScreenStateCode,
            f02IsScreenInteractive,
            f03IsDeviceLocked,
            f04HasCameraPermission,
            f05IsKnownCameraApp.toString(),
            f06PackageInferenceConfidence.toString(),
            f07InferenceMethodCode,
            f08DeltaResumedToTriggerMs.toString(),
            f09RecentActivityCount30s.toString(),
            f10CameraHardwareId,
            f11IsBackCamera
        )
    }

    /**
     * Deterministic numerical feature vector representation.
     * Uses documented sentinels (-1.0 for UNKNOWN/UNVERIFIED/MISSING) to preserve uncertainty
     * without false/denied imputation.
     */
    fun toNumericalVector(): DoubleArray {
        return doubleArrayOf(
            encodeScreenState(f01ScreenStateCode),
            encodeThreeState(f02IsScreenInteractive),
            encodeThreeState(f03IsDeviceLocked),
            encodePermission(f04HasCameraPermission),
            if (f05IsKnownCameraApp) 1.0 else 0.0,
            f06PackageInferenceConfidence.toDouble(),
            encodeInferenceMethod(f07InferenceMethodCode),
            if (f08DeltaResumedToTriggerMs >= 0.0) f08DeltaResumedToTriggerMs else -1.0,
            f09RecentActivityCount30s.toDouble().coerceAtLeast(0.0),
            encodeCameraId(f10CameraHardwareId),
            encodeThreeState(f11IsBackCamera)
        )
    }

    companion object {
        val FEATURE_NAMES = listOf(
            "f01_screen_state_code",
            "f02_is_screen_interactive",
            "f03_is_device_locked",
            "f04_has_camera_permission",
            "f05_is_known_camera_app",
            "f06_package_inference_confidence",
            "f07_inference_method_code",
            "f08_delta_resumed_to_trigger_ms",
            "f09_recent_activity_count_30s",
            "f10_camera_hardware_id",
            "f11_is_back_camera"
        )

        val CSV_HEADER = FEATURE_NAMES.joinToString(",")

        // Deterministic Encodings:
        fun encodeScreenState(state: String): Double = when (state.uppercase()) {
            "OFF" -> 0.0
            "ON_UNLOCKED", "ON" -> 1.0
            "ON_LOCKED" -> 2.0
            else -> -1.0 // UNKNOWN / unpolled
        }

        fun encodeThreeState(value: String): Double = when (value.uppercase()) {
            "FALSE" -> 0.0
            "TRUE" -> 1.0
            else -> -1.0 // UNKNOWN / UNVERIFIED
        }

        fun encodePermission(perm: String): Double = when (perm.uppercase()) {
            "DENIED" -> 0.0
            "GRANTED" -> 1.0
            else -> -1.0 // UNVERIFIED / UNKNOWN
        }

        fun encodeInferenceMethod(method: String): Double = when (method.uppercase()) {
            "NONE" -> 0.0
            "USAGE_STATS_ACTIVITY_RESUMED" -> 1.0
            "USAGE_STATS_ACTIVITY_PAUSED" -> 2.0
            "CAMERA_APP_HEURISTIC" -> 3.0
            else -> -1.0 // UNKNOWN
        }

        fun encodeCameraId(id: String): Double = when (id) {
            "0" -> 0.0 // Rear camera
            "1" -> 1.0 // Front camera
            else -> -1.0 // UNKNOWN / External
        }
    }
}
