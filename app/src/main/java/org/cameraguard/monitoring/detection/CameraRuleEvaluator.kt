package org.cameraguard.monitoring.detection

import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.data.model.ScreenInteractivityState
import org.cameraguard.monitoring.telemetry.InferredPackageContext

data class ClassificationResult(
    val classification: AccessClassification,
    val explanation: String
)

/**
 * Transparent rule-based classifier evaluating observable Android signals.
 *
 * NOTE: Does NOT claim to definitively determine malicious camera access.
 * Classifies events based on observable contextual plausibility.
 */
class CameraRuleEvaluator(
    var flagScreenOffAsUnexpected: Boolean = true,
    var flagDeviceLockedAsUnexpected: Boolean = true,
    var flagMissingPermissionAsUnexpected: Boolean = true
) {

    /**
     * Evaluates raw camera event and contextual telemetry.
     */
    fun evaluate(
        rawEventType: RawCameraEventType,
        screenState: ScreenInteractivityState,
        inferredContext: InferredPackageContext
    ): ClassificationResult {
        // When camera is released back to available, classify as expected lifecycle event
        if (rawEventType == RawCameraEventType.CAMERA_BECAME_AVAILABLE) {
            return ClassificationResult(
                classification = AccessClassification.EXPECTED,
                explanation = "Camera returned to available state (lifecycle closure)."
            )
        }

        // Rule 1: Screen is OFF when camera became unavailable
        if (flagScreenOffAsUnexpected && screenState == ScreenInteractivityState.SCREEN_OFF) {
            return ClassificationResult(
                classification = AccessClassification.UNEXPECTED,
                explanation = "Camera transitioned to unavailable while the screen was OFF. " +
                        "Standard user-facing camera capture normally requires an active display."
            )
        }

        // Rule 2: Device is locked when camera became unavailable
        if (flagDeviceLockedAsUnexpected && screenState == ScreenInteractivityState.SCREEN_ON_LOCKED) {
            return ClassificationResult(
                classification = AccessClassification.UNEXPECTED,
                explanation = "Camera transitioned to unavailable while the device was locked. " +
                        "Unless initiated via verified secure lockscreen camera intent, background access is unexpected."
            )
        }

        // Rule 3: Candidate package inferred, but CAMERA permission is not granted
        if (flagMissingPermissionAsUnexpected &&
            inferredContext.packageName != null &&
            inferredContext.hasCameraPermission == false
        ) {
            return ClassificationResult(
                classification = AccessClassification.UNEXPECTED,
                explanation = "Camera became unavailable while foreground package '${inferredContext.packageName}' " +
                        "was active, but this application does not hold android.permission.CAMERA."
            )
        }

        // Rule 4: Expected user interaction
        if (screenState == ScreenInteractivityState.SCREEN_ON_UNLOCKED &&
            inferredContext.packageName != null &&
            inferredContext.hasCameraPermission == true &&
            (inferredContext.confidence == InferenceConfidence.HIGH || inferredContext.confidence == InferenceConfidence.MEDIUM)
        ) {
            return ClassificationResult(
                classification = AccessClassification.EXPECTED,
                explanation = "Screen is ON and active foreground application '${inferredContext.packageName}' " +
                        "holds CAMERA permission with correlated activity resumption."
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
            classification = AccessClassification.UNKNOWN,
            explanation = reason
        )
    }
}
