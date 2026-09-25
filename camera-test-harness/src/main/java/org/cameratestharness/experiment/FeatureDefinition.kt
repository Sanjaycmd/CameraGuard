package org.cameratestharness.experiment

/**
 * Formal feature metadata and schema definition for Phase 4.4 feature extraction.
 */
data class FeatureDefinition(
    val featureId: String,
    val name: String,
    val type: FeatureType,
    val encoding: String,
    val sourceField: String,
    val sourceLayer: String,
    val temporalWindow: TemporalWindow,
    val observability: Observability,
    val isResearchOnly: Boolean,
    val missingValueRepresentation: String,
    val leakageRisk: LeakageRisk,
    val extractionRule: String,
    val allowedValuesOrRange: String
) {
    enum class FeatureType {
        CATEGORICAL,
        BOOLEAN_3STATE,
        ORDINAL,
        NUMERICAL,
        BOOLEAN
    }

    enum class TemporalWindow {
        T0_ACTIVATION,       // Pre-activation or at trigger moment
        T1_EARLY_SESSION,    // Shortly after activation (e.g. streaming check)
        T2_RETROSPECTIVE     // Post-closure / post-session forensic audit
    }

    enum class Observability {
        PRODUCTION_OBSERVABLE, // Observable by CameraGuard :app via standard APIs
        RESEARCH_ONLY,         // Observable only inside test harness or internal telemetry
        PARTIAL                // Observable with limitations (e.g. UsageStats polling delay)
    }

    enum class LeakageRisk {
        NONE,                  // Strictly safe for real-time T0 model
        TARGET_LEAKAGE,        // Leaks target ground-truth label directly or indirectly
        TEMPORAL_LEAKAGE,      // Uses future/retrospective information unavailable at T0
        OBSERVABILITY_LEAKAGE  // Uses internal target app state invisible to production sandbox
    }

    fun toSchemaCsvRow(): String {
        return listOf(
            featureId,
            name,
            type.name,
            "\"${encoding.replace("\"", "\"\"")}\"",
            sourceField,
            "\"${sourceLayer.replace("\"", "\"\"")}\"",
            temporalWindow.name,
            observability.name,
            isResearchOnly.toString(),
            missingValueRepresentation,
            leakageRisk.name,
            "\"${extractionRule.replace("\"", "\"\"")}\"",
            "\"${allowedValuesOrRange.replace("\"", "\"\"")}\""
        ).joinToString(",")
    }

    companion object {
        val SCHEMA_CSV_HEADER = listOf(
            "feature_id",
            "feature_name",
            "data_type",
            "encoding",
            "source_field",
            "source_layer",
            "temporal_window",
            "observability",
            "is_research_only",
            "missing_value_representation",
            "leakage_risk",
            "extraction_rule",
            "allowed_values_or_range"
        ).joinToString(",")
    }
}

/**
 * Authoritative feature catalog for CameraGuard Phase 4.4.
 * Defines all 20 features (F01..F20) established in Phase 4.1 and audited in Phase 4.3.
 */
object FeatureCatalog {

    val ALL_FEATURES: List<FeatureDefinition> = listOf(
        // F01: screen_state_code
        FeatureDefinition(
            featureId = "F01",
            name = "screen_state_code",
            type = FeatureDefinition.FeatureType.CATEGORICAL,
            encoding = "String code: ON_UNLOCKED, ON_LOCKED, OFF, UNKNOWN. Numerical mapping: OFF=0.0, ON_UNLOCKED=1.0, ON_LOCKED=2.0, UNKNOWN=-1.0",
            sourceField = "screen_state",
            sourceLayer = "CameraGuard ScreenReceiver / BroadcastReceiver (ACTION_SCREEN_ON, ACTION_SCREEN_OFF, ACTION_USER_PRESENT)",
            temporalWindow = FeatureDefinition.TemporalWindow.T0_ACTIVATION,
            observability = FeatureDefinition.Observability.PRODUCTION_OBSERVABLE,
            isResearchOnly = false,
            missingValueRepresentation = "UNKNOWN",
            leakageRisk = FeatureDefinition.LeakageRisk.NONE,
            extractionRule = "Extracted from system screen state broadcast received before or at camera activation",
            allowedValuesOrRange = "ON_UNLOCKED, ON_LOCKED, OFF, UNKNOWN"
        ),

        // F02: is_screen_interactive
        FeatureDefinition(
            featureId = "F02",
            name = "is_screen_interactive",
            type = FeatureDefinition.FeatureType.BOOLEAN_3STATE,
            encoding = "3-State String: TRUE, FALSE, UNKNOWN. Numerical: TRUE=1.0, FALSE=0.0, UNKNOWN=-1.0",
            sourceField = "screen_state",
            sourceLayer = "PowerManager.isInteractive() / ScreenReceiver",
            temporalWindow = FeatureDefinition.TemporalWindow.T0_ACTIVATION,
            observability = FeatureDefinition.Observability.PRODUCTION_OBSERVABLE,
            isResearchOnly = false,
            missingValueRepresentation = "UNKNOWN",
            leakageRisk = FeatureDefinition.LeakageRisk.NONE,
            extractionRule = "TRUE if screen_state in [ON_UNLOCKED, ON_LOCKED, ON]; FALSE if OFF; UNKNOWN if missing",
            allowedValuesOrRange = "TRUE, FALSE, UNKNOWN"
        ),

        // F03: is_device_locked
        FeatureDefinition(
            featureId = "F03",
            name = "is_device_locked",
            type = FeatureDefinition.FeatureType.BOOLEAN_3STATE,
            encoding = "3-State String: TRUE, FALSE, UNKNOWN. Numerical: TRUE=1.0, FALSE=0.0, UNKNOWN=-1.0",
            sourceField = "screen_state",
            sourceLayer = "KeyguardManager.isKeyguardLocked() / ScreenReceiver",
            temporalWindow = FeatureDefinition.TemporalWindow.T0_ACTIVATION,
            observability = FeatureDefinition.Observability.PRODUCTION_OBSERVABLE,
            isResearchOnly = false,
            missingValueRepresentation = "UNKNOWN",
            leakageRisk = FeatureDefinition.LeakageRisk.NONE,
            extractionRule = "TRUE if screen_state is ON_LOCKED; FALSE if ON_UNLOCKED; UNKNOWN if OFF or unverified",
            allowedValuesOrRange = "TRUE, FALSE, UNKNOWN"
        ),

        // F04: has_camera_permission
        FeatureDefinition(
            featureId = "F04",
            name = "has_camera_permission",
            type = FeatureDefinition.FeatureType.BOOLEAN_3STATE,
            encoding = "3-State String: GRANTED, DENIED, UNVERIFIED. Numerical: GRANTED=1.0, DENIED=0.0, UNVERIFIED=-1.0",
            sourceField = "candidate_has_camera_permission",
            sourceLayer = "Production CameraGuard (unprivileged sandbox cannot verify third-party runtime permissions)",
            temporalWindow = FeatureDefinition.TemporalWindow.T0_ACTIVATION,
            observability = FeatureDefinition.Observability.PARTIAL,
            isResearchOnly = false,
            missingValueRepresentation = "UNVERIFIED",
            leakageRisk = FeatureDefinition.LeakageRisk.OBSERVABILITY_LEAKAGE,
            extractionRule = "In production CameraGuard, strictly UNVERIFIED due to Android sandbox boundaries. In research harness, reflects internal target state",
            allowedValuesOrRange = "GRANTED, DENIED, UNVERIFIED, UNKNOWN"
        ),

        // F05: is_known_camera_app
        FeatureDefinition(
            featureId = "F05",
            name = "is_known_camera_app",
            type = FeatureDefinition.FeatureType.BOOLEAN,
            encoding = "Boolean: true, false. Numerical: true=1.0, false=0.0",
            sourceField = "inferred_package_name",
            sourceLayer = "CameraGuard KnownCameraApps / PackageName list lookup",
            temporalWindow = FeatureDefinition.TemporalWindow.T0_ACTIVATION,
            observability = FeatureDefinition.Observability.PRODUCTION_OBSERVABLE,
            isResearchOnly = false,
            missingValueRepresentation = "false",
            leakageRisk = FeatureDefinition.LeakageRisk.NONE,
            extractionRule = "Matches inferredPackageName against known camera/video communication packages or system camera intent",
            allowedValuesOrRange = "true, false"
        ),

        // F06: package_inference_confidence
        FeatureDefinition(
            featureId = "F06",
            name = "package_inference_confidence",
            type = FeatureDefinition.FeatureType.ORDINAL,
            encoding = "Integer ordinal: 0=NONE, 1=LOW, 2=MEDIUM, 3=HIGH. Numerical: 0.0..3.0",
            sourceField = "package_inference_confidence",
            sourceLayer = "CameraGuard UsageStats / ContextualInferenceEngine",
            temporalWindow = FeatureDefinition.TemporalWindow.T0_ACTIVATION,
            observability = FeatureDefinition.Observability.PRODUCTION_OBSERVABLE,
            isResearchOnly = false,
            missingValueRepresentation = "0",
            leakageRisk = FeatureDefinition.LeakageRisk.NONE,
            extractionRule = "Confidence rating assigned by UsageStats attribution heuristic based on event recency and window visibility",
            allowedValuesOrRange = "0, 1, 2, 3"
        ),

        // F07: inference_method_code
        FeatureDefinition(
            featureId = "F07",
            name = "inference_method_code",
            type = FeatureDefinition.FeatureType.CATEGORICAL,
            encoding = "String code: USAGE_STATS_ACTIVITY_RESUMED, USAGE_STATS_ACTIVITY_PAUSED, CAMERA_APP_HEURISTIC, NONE, UNKNOWN. Numerical mapping: NONE=0.0, USAGE_STATS_ACTIVITY_RESUMED=1.0, USAGE_STATS_ACTIVITY_PAUSED=2.0, CAMERA_APP_HEURISTIC=3.0, UNKNOWN=-1.0",
            sourceField = "inference_method",
            sourceLayer = "CameraGuard UsageStats / ContextualInferenceEngine",
            temporalWindow = FeatureDefinition.TemporalWindow.T0_ACTIVATION,
            observability = FeatureDefinition.Observability.PRODUCTION_OBSERVABLE,
            isResearchOnly = false,
            missingValueRepresentation = "UNKNOWN",
            leakageRisk = FeatureDefinition.LeakageRisk.NONE,
            extractionRule = "Identification of heuristic method that attributed the camera acquisition event to a candidate package",
            allowedValuesOrRange = "USAGE_STATS_ACTIVITY_RESUMED, USAGE_STATS_ACTIVITY_PAUSED, CAMERA_APP_HEURISTIC, NONE, UNKNOWN"
        ),

        // F08: delta_resumed_to_trigger_ms
        FeatureDefinition(
            featureId = "F08",
            name = "delta_resumed_to_trigger_ms",
            type = FeatureDefinition.FeatureType.NUMERICAL,
            encoding = "Continuous Double: time delta in milliseconds. Missing/Unavailable sentinel: -1.0",
            sourceField = "detection_latency_ms / UsageStats timestamp delta",
            sourceLayer = "CameraGuard UsageStatsManager queryEvents lookback",
            temporalWindow = FeatureDefinition.TemporalWindow.T0_ACTIVATION,
            observability = FeatureDefinition.Observability.PRODUCTION_OBSERVABLE,
            isResearchOnly = false,
            missingValueRepresentation = "-1.0",
            leakageRisk = FeatureDefinition.LeakageRisk.NONE,
            extractionRule = "Time difference in milliseconds between the candidate app's last ACTIVITY_RESUMED event and the camera availability trigger. Clamped to >= 0.0 or -1.0 if no resume event",
            allowedValuesOrRange = "[-1.0, 30000.0] ms"
        ),

        // F09: recent_activity_count_30s
        FeatureDefinition(
            featureId = "F09",
            name = "recent_activity_count_30s",
            type = FeatureDefinition.FeatureType.NUMERICAL,
            encoding = "Integer: count of foreground transition events in 30s lookback window. Missing: 0",
            sourceField = "UsageStats queryEvents count",
            sourceLayer = "CameraGuard UsageStatsManager lookback window (30s)",
            temporalWindow = FeatureDefinition.TemporalWindow.T0_ACTIVATION,
            observability = FeatureDefinition.Observability.PRODUCTION_OBSERVABLE,
            isResearchOnly = false,
            missingValueRepresentation = "0",
            leakageRisk = FeatureDefinition.LeakageRisk.NONE,
            extractionRule = "Number of ACTIVITY_RESUMED / ACTIVITY_PAUSED transitions across all apps in preceding 30s. Indicates system activity/multitasking intensity",
            allowedValuesOrRange = "[0, 100]"
        ),

        // F10: camera_hardware_id
        FeatureDefinition(
            featureId = "F10",
            name = "camera_hardware_id",
            type = FeatureDefinition.FeatureType.CATEGORICAL,
            encoding = "String: 0 (rear), 1 (front), UNKNOWN. Numerical mapping: 0=0.0, 1=1.0, UNKNOWN=-1.0",
            sourceField = "camera_id",
            sourceLayer = "CameraManager.AvailabilityCallback(cameraId)",
            temporalWindow = FeatureDefinition.TemporalWindow.T0_ACTIVATION,
            observability = FeatureDefinition.Observability.PRODUCTION_OBSERVABLE,
            isResearchOnly = false,
            missingValueRepresentation = "UNKNOWN",
            leakageRisk = FeatureDefinition.LeakageRisk.NONE,
            extractionRule = "Camera hardware identifier passed directly by Android CameraManager availability callback",
            allowedValuesOrRange = "0, 1, UNKNOWN"
        ),

        // F11: is_back_camera
        FeatureDefinition(
            featureId = "F11",
            name = "is_back_camera",
            type = FeatureDefinition.FeatureType.BOOLEAN_3STATE,
            encoding = "3-State String: TRUE, FALSE, UNKNOWN. Numerical: TRUE=1.0, FALSE=0.0, UNKNOWN=-1.0",
            sourceField = "camera_id / CameraCharacteristics.LENS_FACING",
            sourceLayer = "CameraManager.getCameraCharacteristics(cameraId)",
            temporalWindow = FeatureDefinition.TemporalWindow.T0_ACTIVATION,
            observability = FeatureDefinition.Observability.PRODUCTION_OBSERVABLE,
            isResearchOnly = false,
            missingValueRepresentation = "UNKNOWN",
            leakageRisk = FeatureDefinition.LeakageRisk.NONE,
            extractionRule = "TRUE if LENS_FACING_BACK (id 0); FALSE if LENS_FACING_FRONT (id 1); UNKNOWN if unresolvable",
            allowedValuesOrRange = "TRUE, FALSE, UNKNOWN"
        ),

        // --- RETROSPECTIVE / RESEARCH-ONLY FIELDS (T2, NEVER in T0 Production Model) ---

        // F12: session_duration_ms
        FeatureDefinition(
            featureId = "F12",
            name = "session_duration_ms",
            type = FeatureDefinition.FeatureType.NUMERICAL,
            encoding = "Long integer: milliseconds. Missing: 0",
            sourceField = "session_duration_ms",
            sourceLayer = "Test Harness / Room Event closure lifecycle",
            temporalWindow = FeatureDefinition.TemporalWindow.T2_RETROSPECTIVE,
            observability = FeatureDefinition.Observability.RESEARCH_ONLY,
            isResearchOnly = true,
            missingValueRepresentation = "0",
            leakageRisk = FeatureDefinition.LeakageRisk.TEMPORAL_LEAKAGE,
            extractionRule = "Total elapsed time from camera acquisition until physical release. STRICT TEMPORAL LEAKAGE at T0",
            allowedValuesOrRange = "[0, 3600000] ms"
        ),

        // F13: explicit_user_start_action
        FeatureDefinition(
            featureId = "F13",
            name = "explicit_user_start_action",
            type = FeatureDefinition.FeatureType.BOOLEAN_3STATE,
            encoding = "3-State String: TRUE, FALSE, UNKNOWN. Numerical: TRUE=1.0, FALSE=0.0, UNKNOWN=-1.0",
            sourceField = "user_action == USER_PRESSED_START",
            sourceLayer = "Test Harness UI click listener",
            temporalWindow = FeatureDefinition.TemporalWindow.T2_RETROSPECTIVE,
            observability = FeatureDefinition.Observability.RESEARCH_ONLY,
            isResearchOnly = true,
            missingValueRepresentation = "UNKNOWN",
            leakageRisk = FeatureDefinition.LeakageRisk.OBSERVABILITY_LEAKAGE,
            extractionRule = "TRUE if user clicked Start button in harness UI. Invisible to production CameraGuard across sandbox boundary",
            allowedValuesOrRange = "TRUE, FALSE, UNKNOWN"
        ),

        // F14: explicit_user_stop_action
        FeatureDefinition(
            featureId = "F14",
            name = "explicit_user_stop_action",
            type = FeatureDefinition.FeatureType.BOOLEAN_3STATE,
            encoding = "3-State String: TRUE, FALSE, UNKNOWN. Numerical: TRUE=1.0, FALSE=0.0, UNKNOWN=-1.0",
            sourceField = "user_action == USER_PRESSED_STOP",
            sourceLayer = "Test Harness UI click listener",
            temporalWindow = FeatureDefinition.TemporalWindow.T2_RETROSPECTIVE,
            observability = FeatureDefinition.Observability.RESEARCH_ONLY,
            isResearchOnly = true,
            missingValueRepresentation = "UNKNOWN",
            leakageRisk = FeatureDefinition.LeakageRisk.TEMPORAL_LEAKAGE,
            extractionRule = "TRUE if user clicked Stop button in harness UI. STRICT TEMPORAL & OBSERVABILITY LEAKAGE",
            allowedValuesOrRange = "TRUE, FALSE, UNKNOWN"
        ),

        // F15: internal_harness_state
        FeatureDefinition(
            featureId = "F15",
            name = "internal_harness_state",
            type = FeatureDefinition.FeatureType.CATEGORICAL,
            encoding = "String code: IDLE, RUNNING, STOPPING, ERROR, UNKNOWN",
            sourceField = "session_state",
            sourceLayer = "Test Harness internal state machine",
            temporalWindow = FeatureDefinition.TemporalWindow.T2_RETROSPECTIVE,
            observability = FeatureDefinition.Observability.RESEARCH_ONLY,
            isResearchOnly = true,
            missingValueRepresentation = "UNKNOWN",
            leakageRisk = FeatureDefinition.LeakageRisk.OBSERVABILITY_LEAKAGE,
            extractionRule = "Internal target app execution state. Invisible to production CameraGuard",
            allowedValuesOrRange = "IDLE, RUNNING, STOPPING, ERROR, UNKNOWN"
        ),

        // F16: harness_fg_service_active
        FeatureDefinition(
            featureId = "F16",
            name = "harness_fg_service_active",
            type = FeatureDefinition.FeatureType.BOOLEAN,
            encoding = "Boolean: true, false",
            sourceField = "foreground_service_active",
            sourceLayer = "Test Harness CameraTestService lifecycle",
            temporalWindow = FeatureDefinition.TemporalWindow.T2_RETROSPECTIVE,
            observability = FeatureDefinition.Observability.RESEARCH_ONLY,
            isResearchOnly = true,
            missingValueRepresentation = "false",
            leakageRisk = FeatureDefinition.LeakageRisk.OBSERVABILITY_LEAKAGE,
            extractionRule = "TRUE if target app Camera Foreground Service was running. Invisible without privileged system inspection",
            allowedValuesOrRange = "true, false"
        ),

        // F17: time_to_session_close_ms
        FeatureDefinition(
            featureId = "F17",
            name = "time_to_session_close_ms",
            type = FeatureDefinition.FeatureType.NUMERICAL,
            encoding = "Long integer: milliseconds. Missing/Unclosed: -1",
            sourceField = "time_to_session_close_ms",
            sourceLayer = "Camera Availability lifecycle tracking",
            temporalWindow = FeatureDefinition.TemporalWindow.T2_RETROSPECTIVE,
            observability = FeatureDefinition.Observability.RESEARCH_ONLY,
            isResearchOnly = true,
            missingValueRepresentation = "-1",
            leakageRisk = FeatureDefinition.LeakageRisk.TEMPORAL_LEAKAGE,
            extractionRule = "Time elapsed from trigger event until CAMERA_BECAME_AVAILABLE callback. STRICT TEMPORAL LEAKAGE",
            allowedValuesOrRange = "[-1, 3600000] ms"
        ),

        // F18: app_visibility_at_trigger
        FeatureDefinition(
            featureId = "F18",
            name = "app_visibility_at_trigger",
            type = FeatureDefinition.FeatureType.CATEGORICAL,
            encoding = "String code: FOREGROUND, BACKGROUND, UNKNOWN",
            sourceField = "app_visibility",
            sourceLayer = "Test Harness Activity onResume/onStop state",
            temporalWindow = FeatureDefinition.TemporalWindow.T2_RETROSPECTIVE,
            observability = FeatureDefinition.Observability.RESEARCH_ONLY,
            isResearchOnly = true,
            missingValueRepresentation = "UNKNOWN",
            leakageRisk = FeatureDefinition.LeakageRisk.OBSERVABILITY_LEAKAGE,
            extractionRule = "Target app window visibility at exact moment of trigger. Production has only UsageStats heuristic",
            allowedValuesOrRange = "FOREGROUND, BACKGROUND, UNKNOWN"
        ),

        // F19: backgrounded_during_session
        FeatureDefinition(
            featureId = "F19",
            name = "backgrounded_during_session",
            type = FeatureDefinition.FeatureType.BOOLEAN,
            encoding = "Boolean: true, false",
            sourceField = "app_visibility transitioned to BACKGROUND while camera open",
            sourceLayer = "Test Harness / CameraGuard Event log sequence",
            temporalWindow = FeatureDefinition.TemporalWindow.T2_RETROSPECTIVE,
            observability = FeatureDefinition.Observability.RESEARCH_ONLY,
            isResearchOnly = true,
            missingValueRepresentation = "false",
            leakageRisk = FeatureDefinition.LeakageRisk.TEMPORAL_LEAKAGE,
            extractionRule = "TRUE if app moved to background after camera was acquired. Available only during session progression",
            allowedValuesOrRange = "true, false"
        ),

        // F20: returned_to_foreground
        FeatureDefinition(
            featureId = "F20",
            name = "returned_to_foreground",
            type = FeatureDefinition.FeatureType.BOOLEAN,
            encoding = "Boolean: true, false",
            sourceField = "app_visibility transitioned back to FOREGROUND before close",
            sourceLayer = "Test Harness / CameraGuard Event log sequence",
            temporalWindow = FeatureDefinition.TemporalWindow.T2_RETROSPECTIVE,
            observability = FeatureDefinition.Observability.RESEARCH_ONLY,
            isResearchOnly = true,
            missingValueRepresentation = "false",
            leakageRisk = FeatureDefinition.LeakageRisk.TEMPORAL_LEAKAGE,
            extractionRule = "TRUE if backgrounded app returned to foreground before closing camera. STRICT TEMPORAL LEAKAGE",
            allowedValuesOrRange = "true, false"
        )
    )

    fun getT0Features(): List<FeatureDefinition> {
        return ALL_FEATURES.filter { it.temporalWindow == FeatureDefinition.TemporalWindow.T0_ACTIVATION }
    }

    fun getT1Features(): List<FeatureDefinition> {
        return ALL_FEATURES.filter { it.temporalWindow == FeatureDefinition.TemporalWindow.T1_EARLY_SESSION }
    }

    fun getT2Features(): List<FeatureDefinition> {
        return ALL_FEATURES.filter { it.temporalWindow == FeatureDefinition.TemporalWindow.T2_RETROSPECTIVE }
    }

    fun getProductionObservableFeatures(): List<FeatureDefinition> {
        return ALL_FEATURES.filter { it.observability == FeatureDefinition.Observability.PRODUCTION_OBSERVABLE }
    }

    fun getResearchOnlyFeatures(): List<FeatureDefinition> {
        return ALL_FEATURES.filter { it.isResearchOnly }
    }

    fun toSchemaCsv(): String {
        val lines = mutableListOf<String>()
        lines.add(FeatureDefinition.SCHEMA_CSV_HEADER)
        for (f in ALL_FEATURES) {
            lines.add(f.toSchemaCsvRow())
        }
        return lines.joinToString("\n")
    }
}
