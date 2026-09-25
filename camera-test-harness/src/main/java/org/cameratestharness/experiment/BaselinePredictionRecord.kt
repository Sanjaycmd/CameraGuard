package org.cameratestharness.experiment

/**
 * Structured baseline evaluation record for a reconstructed session.
 * Captures the Phase 2 rule engine prediction alongside audit metadata,
 * ensuring complete isolation from ground truth during prediction.
 */
data class BaselinePredictionRecord(
    val sessionId: String,
    val auditScenarioId: String,
    val repetition: Int,
    val groundTruthContext: String,
    val actualCameraActivity: Boolean,
    val cameraLifecycle: String,
    val baselineClassification: String, // "EXPECTED", "UNEXPECTED", "UNKNOWN", "NO_CAMERA_EVENT"
    val baselineExplanation: String,
    val ruleTriggered: String,
    val inferredPackageName: String?,
    val packageConfidence: String,
    val inferenceMethod: String,
    val screenState: String,
    val permissionState: String,
    val evaluationSubset: String, // "CAMERA_ACTIVATION", "NO_CAMERA_CONTROL"
    val isDecisive: Boolean,
    val inclusionStatus: String, // "INCLUDED", "EXCLUDED"
    val exclusionReason: String?
) {
    fun toCsvRow(): String {
        return listOf(
            sessionId,
            auditScenarioId,
            repetition.toString(),
            groundTruthContext,
            actualCameraActivity.toString(),
            cameraLifecycle,
            baselineClassification,
            "\"${baselineExplanation.replace("\"", "\"\"")}\"",
            ruleTriggered,
            inferredPackageName ?: "NONE",
            packageConfidence,
            inferenceMethod,
            screenState,
            permissionState,
            evaluationSubset,
            isDecisive.toString(),
            inclusionStatus,
            exclusionReason ?: "NONE"
        ).joinToString(",")
    }

    companion object {
        val CSV_HEADER = listOf(
            "session_id",
            "audit_scenario_id",
            "repetition",
            "ground_truth_context",
            "actual_camera_activity",
            "camera_lifecycle",
            "baseline_classification",
            "baseline_explanation",
            "rule_triggered",
            "inferred_package_name",
            "package_confidence",
            "inference_method",
            "screen_state",
            "permission_state",
            "evaluation_subset",
            "is_decisive",
            "inclusion_status",
            "exclusion_reason"
        ).joinToString(",")
    }
}
