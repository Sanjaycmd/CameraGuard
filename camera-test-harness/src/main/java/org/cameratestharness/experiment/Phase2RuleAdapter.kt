package org.cameratestharness.experiment

/**
 * Deterministic research adapter faithfully mirroring the exact Phase 2 production
 * detection logic from org.cameraguard.monitoring.detection.CameraRuleEvaluator.
 *
 * Maintains strict zero-change isolation for the :app production module while allowing
 * reproducible offline evaluation on historical experimental telemetry.
 */
object Phase2RuleAdapter {

    enum class RawEventType {
        CAMERA_BECAME_UNAVAILABLE,
        CAMERA_BECAME_AVAILABLE
    }

    enum class ScreenState {
        SCREEN_ON_UNLOCKED,
        SCREEN_ON_LOCKED,
        SCREEN_OFF,
        UNKNOWN
    }

    enum class Confidence {
        NONE,
        LOW,
        MEDIUM,
        HIGH
    }

    enum class Method {
        NONE,
        USAGE_STATS_ACTIVITY_RESUMED,
        USAGE_STATS_ACTIVITY_PAUSED,
        USAGE_STATS_FALLBACK,
        CAMERA_APP_HEURISTIC,
        UNKNOWN
    }

    enum class Classification {
        EXPECTED,
        UNEXPECTED,
        UNKNOWN
    }

    data class InferredContext(
        val packageName: String?,
        val confidence: Confidence,
        val method: Method,
        val hasCameraPermission: Boolean?,
        val deltaFromEventMs: Long?
    )

    data class ClassificationResult(
        val classification: Classification,
        val explanation: String,
        val ruleTriggered: String
    )

    data class RuleFlags(
        val flagScreenOffAsUnexpected: Boolean = true,
        val flagDeviceLockedAsUnexpected: Boolean = true,
        val flagMissingPermissionAsUnexpected: Boolean = true
    )

    /**
     * Evaluates a camera availability transition and contextual telemetry.
     * Replicates the exact rule sequence of CameraRuleEvaluator.kt:
     *
     * 1. Lifecycle Closure: CAMERA_BECAME_AVAILABLE -> EXPECTED
     * 2. Rule 1: Screen is OFF -> UNEXPECTED
     * 3. Rule 2: Device is locked (SCREEN_ON_LOCKED) -> UNEXPECTED
     * 4. Rule 3: Candidate package active, but permission is explicitly FALSE -> UNEXPECTED
     * 5. Rule 4: Screen ON_UNLOCKED + candidate has permission TRUE + (HIGH or MEDIUM confidence) -> EXPECTED
     * 6. Rule 5: Otherwise -> UNKNOWN (Inconclusive telemetry)
     */
    fun evaluate(
        rawEventType: RawEventType,
        screenState: ScreenState,
        inferredContext: InferredContext,
        flags: RuleFlags = RuleFlags()
    ): ClassificationResult {
        // Lifecycle Closure
        if (rawEventType == RawEventType.CAMERA_BECAME_AVAILABLE) {
            return ClassificationResult(
                classification = Classification.EXPECTED,
                explanation = "Camera returned to available state (lifecycle closure).",
                ruleTriggered = "LIFECYCLE_CLOSURE"
            )
        }

        // Rule 1: Screen is OFF when camera became unavailable
        if (flags.flagScreenOffAsUnexpected && screenState == ScreenState.SCREEN_OFF) {
            return ClassificationResult(
                classification = Classification.UNEXPECTED,
                explanation = "Camera transitioned to unavailable while the screen was OFF. " +
                        "Standard user-facing camera capture normally requires an active display.",
                ruleTriggered = "RULE_1_SCREEN_OFF"
            )
        }

        // Rule 2: Device is locked when camera became unavailable
        if (flags.flagDeviceLockedAsUnexpected && screenState == ScreenState.SCREEN_ON_LOCKED) {
            return ClassificationResult(
                classification = Classification.UNEXPECTED,
                explanation = "Camera transitioned to unavailable while the device was locked. " +
                        "Unless initiated via verified secure lockscreen camera intent, background access is unexpected.",
                ruleTriggered = "RULE_2_DEVICE_LOCKED"
            )
        }

        // Rule 3: Candidate package inferred, but CAMERA permission is not granted (explicitly false)
        if (flags.flagMissingPermissionAsUnexpected &&
            inferredContext.packageName != null &&
            inferredContext.hasCameraPermission == false
        ) {
            return ClassificationResult(
                classification = Classification.UNEXPECTED,
                explanation = "Camera became unavailable while foreground package '${inferredContext.packageName}' " +
                        "was active, but this application does not hold android.permission.CAMERA.",
                ruleTriggered = "RULE_3_MISSING_PERMISSION"
            )
        }

        // Rule 4: Expected user interaction
        if (screenState == ScreenState.SCREEN_ON_UNLOCKED &&
            inferredContext.packageName != null &&
            inferredContext.hasCameraPermission == true &&
            (inferredContext.confidence == Confidence.HIGH || inferredContext.confidence == Confidence.MEDIUM)
        ) {
            return ClassificationResult(
                classification = Classification.EXPECTED,
                explanation = "Screen is ON and active foreground application '${inferredContext.packageName}' " +
                        "holds CAMERA permission with correlated activity resumption.",
                ruleTriggered = "RULE_4_EXPECTED_USER_INTERACTION"
            )
        }

        // Rule 5: Unknown / Inconclusive
        val reason = buildString {
            append("Inconclusive telemetry: ")
            if (inferredContext.packageName == null) {
                append("Foreground application could not be reliably correlated (UsageStats unavailable or outside window). ")
            } else {
                append("Inference confidence is ${inferredContext.confidence}. ")
            }
            if (inferredContext.hasCameraPermission == null && inferredContext.packageName != null) {
                append("Camera permission could not be verified from sandbox. ")
            }
            append("Screen state was $screenState.")
        }

        return ClassificationResult(
            classification = Classification.UNKNOWN,
            explanation = reason,
            ruleTriggered = "RULE_5_INCONCLUSIVE_UNKNOWN"
        )
    }
}
