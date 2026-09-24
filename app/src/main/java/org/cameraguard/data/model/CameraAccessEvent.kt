package org.cameraguard.data.model

import java.util.UUID

/**
 * Core event model representing a camera availability transition along with
 * contextual device telemetry and transparent classification.
 *
 * Research Separation:
 * 1. Raw Observation: [rawEventType], [timestamp], [cameraId], [screenState]
 * 2. Inference: [inferredPackageName], [packageInferenceConfidence], [inferenceMethod], [candidateHasCameraPermission]
 * 3. Classification: [classification], [classificationExplanation], [detectionLatencyMs], [isSynthetic]
 */
data class CameraAccessEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val rawEventType: RawCameraEventType,
    val cameraId: String? = null,
    val screenState: ScreenInteractivityState = ScreenInteractivityState.UNKNOWN,
    val inferredPackageName: String? = null,
    val packageInferenceConfidence: InferenceConfidence = InferenceConfidence.NONE,
    val inferenceMethod: InferenceMethod = InferenceMethod.NONE,
    val candidateHasCameraPermission: Boolean? = null,
    val classification: AccessClassification = AccessClassification.UNKNOWN,
    val classificationExplanation: String = "",
    val detectionLatencyMs: Long? = null,
    val isSynthetic: Boolean = false
) {
    /**
     * Backward-compatibility constructor for Phase 1 code.
     */
    constructor(
        timestamp: Long = System.currentTimeMillis(),
        source: String = "Unknown Application",
        isUnexpected: Boolean = false
    ) : this(
        timestamp = timestamp,
        rawEventType = if (isUnexpected) RawCameraEventType.CAMERA_BECAME_UNAVAILABLE else RawCameraEventType.CAMERA_BECAME_AVAILABLE,
        inferredPackageName = source,
        classification = if (isUnexpected) AccessClassification.UNEXPECTED else AccessClassification.EXPECTED,
        classificationExplanation = if (isUnexpected) "Event marked as unexpected" else "Event marked as expected"
    )

    /**
     * Backward-compatibility accessor for Phase 1 code.
     */
    val isUnexpected: Boolean
        get() = classification == AccessClassification.UNEXPECTED

    val source: String
        get() = inferredPackageName ?: "Unknown Application"
}
