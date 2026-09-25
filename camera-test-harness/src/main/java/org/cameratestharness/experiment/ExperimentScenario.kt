package org.cameratestharness.experiment

enum class ExperimentScenario(
    val id: String,
    val title: String,
    val description: String,
    val defaultGroundTruth: GroundTruthContext,
    val isCameraActivation: Boolean = true
) {
    NORMAL_FOREGROUND_CAMERA(
        id = "NORMAL_FOREGROUND_CAMERA",
        title = "Normal Foreground Camera",
        description = "User starts camera; session remains active while Activity is visible in foreground.",
        defaultGroundTruth = GroundTruthContext.USER_INITIATED_FOREGROUND,
        isCameraActivation = true
    ),
    BACKGROUND_CAMERA_CONTINUATION(
        id = "BACKGROUND_CAMERA_CONTINUATION",
        title = "Background Camera Continuation",
        description = "User starts camera, navigates to Home; foreground service continues active camera streaming.",
        defaultGroundTruth = GroundTruthContext.USER_INITIATED_BACKGROUND_CONTINUATION,
        isCameraActivation = true
    ),
    CAMERA_START_STOP(
        id = "CAMERA_START_STOP",
        title = "Camera Start/Stop",
        description = "User starts camera, establishes session, and subsequently stops camera cleanly.",
        defaultGroundTruth = GroundTruthContext.USER_INITIATED_FOREGROUND,
        isCameraActivation = true
    ),
    PERMISSION_DENIED(
        id = "PERMISSION_DENIED",
        title = "Permission Denied",
        description = "Camera permission missing or denied; camera hardware acquisition must not proceed.",
        defaultGroundTruth = GroundTruthContext.PERMISSION_DENIED,
        isCameraActivation = false
    ),
    PERMISSION_GRANTED_NO_CAMERA(
        id = "PERMISSION_GRANTED_NO_CAMERA",
        title = "Permission Granted / No Camera",
        description = "Camera permission is held, but no camera hardware acquisition is initiated.",
        defaultGroundTruth = GroundTruthContext.NO_CAMERA_ACTIVITY,
        isCameraActivation = false
    ),
    CAMERA_SESSION_CLOSED(
        id = "CAMERA_SESSION_CLOSED",
        title = "Camera Session Closed",
        description = "Active camera session is terminated by explicit user stop action.",
        defaultGroundTruth = GroundTruthContext.CAMERA_SESSION_CLOSED,
        isCameraActivation = true
    ),
    AMBIGUOUS_CONTEXT(
        id = "AMBIGUOUS_CONTEXT",
        title = "Ambiguous Context (Observation Only)",
        description = "Telemetry does not decisively establish intent; observation-only, no camera activation.",
        defaultGroundTruth = GroundTruthContext.AMBIGUOUS_CONTEXT,
        isCameraActivation = false
    );

    companion object {
        fun fromId(id: String): ExperimentScenario? = entries.find { it.id == id }
    }
}
