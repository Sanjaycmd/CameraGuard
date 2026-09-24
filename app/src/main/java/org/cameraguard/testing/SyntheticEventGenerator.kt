package org.cameraguard.testing

import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.CameraAccessEvent
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.data.model.ScreenInteractivityState
import java.util.UUID

/**
 * Generates clearly distinguished test events to validate the notification
 * dispatch, database persistence, and UI rendering pipeline without spoofing
 * real hardware signals.
 *
 * NOTE: All events generated here are explicitly flagged with [isSynthetic = true].
 */
object SyntheticEventGenerator {

    fun createScreenOffUnexpectedEvent(): CameraAccessEvent {
        return CameraAccessEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            cameraId = "0",
            screenState = ScreenInteractivityState.SCREEN_OFF,
            inferredPackageName = "com.demo.covert.background.service",
            packageInferenceConfidence = InferenceConfidence.HIGH,
            inferenceMethod = InferenceMethod.MANUAL_SYNTHETIC,
            candidateHasCameraPermission = false,
            classification = AccessClassification.UNEXPECTED,
            classificationExplanation = "[TEST PIPELINE] Simulated camera unavailable transition while screen was OFF with no verified permission.",
            detectionLatencyMs = 12L,
            isSynthetic = true
        )
    }

    fun createNormalExpectedEvent(): CameraAccessEvent {
        return CameraAccessEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            cameraId = "0",
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredPackageName = "com.google.android.GoogleCamera",
            packageInferenceConfidence = InferenceConfidence.HIGH,
            inferenceMethod = InferenceMethod.MANUAL_SYNTHETIC,
            candidateHasCameraPermission = true,
            classification = AccessClassification.EXPECTED,
            classificationExplanation = "[TEST PIPELINE] Simulated normal camera launch with active screen and verified CAMERA permission.",
            detectionLatencyMs = 8L,
            isSynthetic = true
        )
    }

    fun createUnknownContextEvent(): CameraAccessEvent {
        return CameraAccessEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            cameraId = "1",
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredPackageName = null,
            packageInferenceConfidence = InferenceConfidence.NONE,
            inferenceMethod = InferenceMethod.NONE,
            candidateHasCameraPermission = null,
            classification = AccessClassification.UNKNOWN,
            classificationExplanation = "[TEST PIPELINE] Simulated inconclusive event: Screen is ON but foreground app correlation yielded no candidate.",
            detectionLatencyMs = 15L,
            isSynthetic = true
        )
    }

    fun createCameraAvailableEvent(): CameraAccessEvent {
        return CameraAccessEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            rawEventType = RawCameraEventType.CAMERA_BECAME_AVAILABLE,
            cameraId = "0",
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredPackageName = "com.google.android.GoogleCamera",
            packageInferenceConfidence = InferenceConfidence.HIGH,
            inferenceMethod = InferenceMethod.MANUAL_SYNTHETIC,
            candidateHasCameraPermission = true,
            classification = AccessClassification.EXPECTED,
            classificationExplanation = "[TEST PIPELINE] Camera returned to available state.",
            detectionLatencyMs = 5L,
            isSynthetic = true
        )
    }
}
