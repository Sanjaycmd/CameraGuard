package org.cameratestharness.experiment

/**
 * Cleanly separated, leak-free derived session representation for Phase 4.4 ML evaluations.
 * Strictly isolates production-observable features from research ground truth and retrospective signals.
 */
data class DerivedMlRecord(
    // 1. Identification (For audit & grouping only, NEVER input to ML model)
    val sessionId: String,
    val auditScenarioId: String,
    val repetition: Int,
    val timestampIso: String,

    // 2. Ground Truth (Target variable)
    val groundTruthContext: String,

    // 3. Production-Observable Features at Activation Point (T0)
    // F01: Screen state code [ON_UNLOCKED, ON_LOCKED, OFF, UNKNOWN]
    val f01ScreenStateCode: String,
    // F02: Screen interactive boolean [TRUE, FALSE, UNKNOWN]
    val f02IsScreenInteractive: String,
    // F03: Device locked boolean [TRUE, FALSE, UNKNOWN]
    val f03IsDeviceLocked: String,
    // F04: Verified camera permission held [TRUE, FALSE, UNKNOWN]
    val f04HasCameraPermission: String,
    // F05: Known camera app heuristic [true, false]
    val f05IsKnownCameraApp: Boolean,
    // F06: Package inference confidence ordinal [0..3]
    val f06PackageInferenceConfidence: Int,
    // F07: Inference method code [USAGE_STATS_RESUMED, CAMERA_APP_HEURISTIC, NONE]
    val f07InferenceMethodCode: String,
    // F08: Time delta from last app resume to camera trigger ms (-1.0 if unavailable)
    val f08DeltaResumedToTriggerMs: Double,
    // F09: Recent foreground activity count in 30s lookback window
    val f09RecentActivityCount30s: Int,
    // F10: Camera hardware sensor ID ["0", "1", "UNKNOWN"]
    val f10CameraHardwareId: String,
    // F11: Back camera sensor boolean [TRUE, FALSE, UNKNOWN]
    val f11IsBackCamera: String,

    // 4. Research-Only / Retrospective Fields (T2 - Available ONLY Post-Closure, NEVER in Real-Time T0 Model)
    // F12: Retrospective total camera session duration ms
    val f12SessionDurationMs: Long,
    // F13: Explicit user start button press (Harness internal) [TRUE, FALSE, UNKNOWN]
    val f13ExplicitUserStartAction: String,
    // F14: Explicit user stop button press (Harness internal) [TRUE, FALSE, UNKNOWN]
    val f14ExplicitUserStopAction: String,
    // F15: Internal test harness state machine code
    val f15InternalHarnessState: String,
    // F16: Harness verified camera foreground service active
    val f16HarnessFgServiceActive: Boolean,
    // F17: Time from trigger until camera close ms (-1 if unclosed)
    val f17TimeToSessionCloseMs: Long,
    // F18: Activity visibility at trigger point [FOREGROUND, BACKGROUND, UNKNOWN]
    val f18AppVisibilityAtTrigger: String,
    // F19: App transitioned to background while camera remained active
    val f19BackgroundedDuringSession: Boolean,
    // F20: App returned to foreground while camera remained active
    val f20ReturnedToForeground: Boolean,

    // 5. Temporal Availability Metadata
    val featureTemporalWindow: String
) {
    companion object {
        const val TEMPORAL_WINDOW_T0_ACTIVATION = "T0_ACTIVATION"
        const val TEMPORAL_WINDOW_T1_EARLY_SESSION = "T1_EARLY_SESSION"
        const val TEMPORAL_WINDOW_T2_POST_CLOSURE = "T2_POST_CLOSURE"

        val CSV_HEADER = listOf(
            "session_id", "audit_scenario_id", "repetition", "timestamp_iso",
            "ground_truth_context",
            "f01_screen_state_code", "f02_is_screen_interactive", "f03_is_device_locked",
            "f04_has_camera_permission", "f05_is_known_camera_app", "f06_package_inference_confidence",
            "f07_inference_method_code", "f08_delta_resumed_to_trigger_ms", "f09_recent_activity_count_30s",
            "f10_camera_hardware_id", "f11_is_back_camera",
            "f12_session_duration_ms", "f13_explicit_user_start_action", "f14_explicit_user_stop_action",
            "f15_internal_harness_state", "f16_harness_fg_service_active", "f17_time_to_session_close_ms",
            "f18_app_visibility_at_trigger", "f19_backgrounded_during_session", "f20_returned_to_foreground",
            "feature_temporal_window"
        ).joinToString(",")
    }

    fun toCsvRow(): String {
        return listOf(
            sessionId, auditScenarioId, repetition.toString(), timestampIso,
            groundTruthContext,
            f01ScreenStateCode, f02IsScreenInteractive, f03IsDeviceLocked,
            f04HasCameraPermission, f05IsKnownCameraApp.toString(), f06PackageInferenceConfidence.toString(),
            f07InferenceMethodCode, f08DeltaResumedToTriggerMs.toString(), f09RecentActivityCount30s.toString(),
            f10CameraHardwareId, f11IsBackCamera,
            f12SessionDurationMs.toString(), f13ExplicitUserStartAction, f14ExplicitUserStopAction,
            f15InternalHarnessState, f16HarnessFgServiceActive.toString(), f17TimeToSessionCloseMs.toString(),
            f18AppVisibilityAtTrigger, f19BackgroundedDuringSession.toString(), f20ReturnedToForeground.toString(),
            featureTemporalWindow
        ).joinToString(",")
    }
}
