package org.cameraguard.monitoring.detection.hybrid

/**
 * Real-time production ML feature vector strictly restricted to the T0 activation window.
 *
 * Structurally guarantees:
 * 1. F04 is clamped to UNVERIFIED (-1.0).
 * 2. Zero retrospective (T2) features (duration, close timestamps, fgs, etc.) are present.
 * 3. Zero scenario/label/target fields are present.
 * 4. Only real-time signals available at T0 (activation time) are captured.
 */
data class ProductionT0FeatureVector(
    // F01: Screen state code [0.0 = OFF, 1.0 = ON_UNLOCKED, 2.0 = ON_LOCKED, -1.0 = UNKNOWN]
    val f01ScreenState: Double,

    // F02: Screen is interactive [0.0 = FALSE, 1.0 = TRUE, -1.0 = UNKNOWN]
    val f02IsInteractive: Double,

    // F03: Device is locked [0.0 = FALSE, 1.0 = TRUE, -1.0 = UNKNOWN]
    val f03IsLocked: Double,

    // F04: Target application camera permission status [-1.0 = UNVERIFIED]
    // In production CameraGuard, strictly -1.0 due to Android UID/SELinux sandbox boundaries.
    val f04PermClamped: Double,

    // F05: Inferred app matches known camera/video communication package [1.0 = true, 0.0 = false]
    val f05KnownCameraApp: Double,

    // F06: Package attribution confidence rating [0.0 = NONE, 1.0 = LOW, 2.0 = MEDIUM, 3.0 = HIGH]
    val f06PackageConfidence: Double,

    // F07: Heuristic inference method code [0.0 = NONE, 1.0 = USAGE_STATS_RESUMED, 2.0 = PAUSED, 3.0 = HEURISTIC/ACCESSIBILITY, -1.0 = UNKNOWN]
    val f07InferenceMethod: Double,

    // F08: Time delta in ms from last app resume to camera trigger [-1.0 if unavailable]
    val f08DeltaResumedMs: Double,

    // F09: Recent activity transition count in 30s lookback window [>= 0.0]
    val f09RecentActivityCount: Double,

    // F10: Camera hardware sensor ID [0.0 = "0", 1.0 = "1", -1.0 = UNKNOWN]
    val f10CameraId: Double,

    // F11: Lens facing is back camera [1.0 = TRUE, 0.0 = FALSE, -1.0 = UNKNOWN]
    val f11IsBackCamera: Double
) {
    init {
        require(f04PermClamped == PERMISSION_UNVERIFIED) {
            "LEAKAGE VIOLATION: F04 must remain clamped to UNVERIFIED (-1.0) in production mode, got $f04PermClamped"
        }
    }

    fun toArray(): DoubleArray = doubleArrayOf(
        f01ScreenState,
        f02IsInteractive,
        f03IsLocked,
        f04PermClamped,
        f05KnownCameraApp,
        f06PackageConfidence,
        f07InferenceMethod,
        f08DeltaResumedMs,
        f09RecentActivityCount,
        f10CameraId,
        f11IsBackCamera
    )

    companion object {
        const val PERMISSION_UNVERIFIED = -1.0

        val FEATURE_NAMES = listOf(
            "f01_screen_state",
            "f02_is_interactive",
            "f03_is_locked",
            "f04_perm_clamped",
            "f05_known_camera_app",
            "f06_package_confidence",
            "f07_inference_method",
            "f08_delta_resumed_ms",
            "f09_recent_activity_count",
            "f10_camera_id",
            "f11_is_back_camera"
        )

        val PROHIBITED_FEATURE_TERMS = listOf(
            "duration", "stop", "start_action", "stop_action",
            "harness", "fgs", "visibility", "backgrounded", "returned",
            "target", "scenario", "ground_truth", "exclusion", "user_action",
            "session_id", "session_close", "close_ms",
            "f12", "f13", "f14", "f15", "f16", "f17", "f18", "f19", "f20"
        )

        fun assertFeatureSafety(names: List<String>) {
            for (name in names) {
                val lower = name.lowercase()
                for (term in PROHIBITED_FEATURE_TERMS) {
                    if (lower.contains(term)) {
                        throw IllegalArgumentException("LEAKAGE DETECTED: Prohibited substring '$term' in feature '$name'")
                    }
                }
            }
        }
    }
}
