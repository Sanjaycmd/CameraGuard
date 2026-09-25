package org.cameratestharness.experiment

/**
 * Represents a logically reconstructed experiment session aggregated from raw event telemetry.
 */
data class ReconstructedSession(
    val sessionId: String,
    val primaryScenarioId: String,
    val allScenarioIds: List<String>,
    val repetition: Int,
    val startTimestamp: String,
    val endTimestamp: String,
    val durationMs: Long,
    val orderedEvents: List<ExperimentRecord>,
    val cameraLifecycle: CameraLifecycleState,
    val permissionState: String,
    val activityLifecycle: String,
    val appVisibility: String,
    val foregroundServiceActive: Boolean,
    val screenState: String,
    val recentUserInteraction: Boolean?,
    val inferredPackageName: String,
    val cameraAvailability: String,
    val groundTruthContext: String,
    val allGroundTruthContexts: List<String>,
    val triggerMechanism: String,
    val isValidLifecycle: Boolean,
    val lifecycleIssues: List<String>,
    val hasDuplicates: Boolean,
    val duplicateEventCount: Int,
    val isContradictoryGroundTruth: Boolean,
    val reviewRequired: Boolean,
    val reviewReasons: List<String>,
    val isExcludedFromMl: Boolean,
    val exclusionReason: String?
) {
    enum class CameraLifecycleState {
        NO_CAMERA,
        OPENED_AND_CLOSED,
        OPENED_UNCLOSED,
        CLOSED_WITHOUT_OPEN,
        ANOMALOUS
    }
}
