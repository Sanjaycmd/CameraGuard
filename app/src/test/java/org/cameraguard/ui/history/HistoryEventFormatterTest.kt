package org.cameraguard.ui.history

import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.CameraAccessEvent
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.data.model.ScreenInteractivityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class HistoryEventFormatterTest {

    private val testDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val fixedTimestamp = 1700000000000L // 2023-11-14 22:13:20 UTC

    /**
     * Requirement: Copy All with zero events
     */
    @Test
    fun testFormatHistory_zeroEvents() {
        val result = HistoryEventFormatter.formatHistory(emptyList(), testDateFormat)
        assertEquals("CameraGuard History: No events recorded.", result)
    }

    /**
     * Requirement: Copy one individual event
     */
    @Test
    fun testFormatEvent_individualEvent() {
        val event = CameraAccessEvent(
            timestamp = fixedTimestamp,
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            cameraId = "0",
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredPackageName = "com.whatsapp",
            packageInferenceConfidence = InferenceConfidence.HIGH,
            inferenceMethod = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            candidateHasCameraPermission = false,
            classification = AccessClassification.UNEXPECTED,
            classificationExplanation = "Screen ON but app lacks camera permission.",
            detectionLatencyMs = 15L,
            isSynthetic = false
        )

        val formatted = HistoryEventFormatter.formatEvent(event, testDateFormat)

        assertTrue(formatted.contains("Classification: UNEXPECTED"))
        assertFalse(formatted.contains("Synthetic: YES"))
        assertTrue(formatted.contains("Timestamp: 2023-11-14 22:13:20"))
        assertTrue(formatted.contains("Raw Event: CAMERA_BECAME_UNAVAILABLE"))
        assertTrue(formatted.contains("Camera ID: 0"))
        assertTrue(formatted.contains("Inferred App: com.whatsapp"))
        assertTrue(formatted.contains("Confidence: HIGH"))
        assertTrue(formatted.contains("Inference Method: USAGE_STATS_ACTIVITY_RESUMED"))
        assertTrue(formatted.contains("Screen State: SCREEN_ON_UNLOCKED"))
        assertTrue(formatted.contains("Camera Permission: NOT GRANTED"))
        assertTrue(formatted.contains("Rationale: Screen ON but app lacks camera permission."))
        assertTrue(formatted.contains("Evaluation Latency: 15ms"))
    }

    /**
     * Requirement: UNKNOWN event formatting
     */
    @Test
    fun testFormatEvent_unknownClassification() {
        val event = CameraAccessEvent(
            timestamp = fixedTimestamp,
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            cameraId = null,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredPackageName = null,
            packageInferenceConfidence = InferenceConfidence.NONE,
            inferenceMethod = InferenceMethod.NONE,
            candidateHasCameraPermission = null,
            classification = AccessClassification.UNKNOWN,
            classificationExplanation = "Inconclusive telemetry.",
            detectionLatencyMs = null,
            isSynthetic = true
        )

        val formatted = HistoryEventFormatter.formatEvent(event, testDateFormat)

        assertTrue(formatted.contains("Classification: UNKNOWN"))
        assertTrue(formatted.contains("Synthetic: YES"))
        assertTrue(formatted.contains("Camera ID: N/A"))
        assertTrue(formatted.contains("Inferred App: None / Uncorrelated"))
        assertTrue(formatted.contains("Confidence: NONE"))
        assertTrue(formatted.contains("Inference Method: NONE"))
        assertTrue(formatted.contains("Camera Permission: UNVERIFIED"))
        assertTrue(formatted.contains("Rationale: Inconclusive telemetry."))
        assertFalse(formatted.contains("Evaluation Latency:"))
    }

    /**
     * Requirement: EXPECTED event formatting
     */
    @Test
    fun testFormatEvent_expectedClassification() {
        val event = CameraAccessEvent(
            timestamp = fixedTimestamp,
            rawEventType = RawCameraEventType.CAMERA_BECAME_AVAILABLE,
            cameraId = "1",
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredPackageName = "com.android.camera",
            packageInferenceConfidence = InferenceConfidence.HIGH,
            inferenceMethod = InferenceMethod.USAGE_STATS_FALLBACK,
            candidateHasCameraPermission = true,
            classification = AccessClassification.EXPECTED,
            classificationExplanation = "Camera session closed normally.",
            detectionLatencyMs = 0L,
            isSynthetic = false
        )

        val formatted = HistoryEventFormatter.formatEvent(event, testDateFormat)

        assertTrue(formatted.contains("Classification: EXPECTED"))
        assertTrue(formatted.contains("Camera ID: 1"))
        assertTrue(formatted.contains("Inferred App: com.android.camera"))
        assertTrue(formatted.contains("Inference Method: USAGE_STATS_FALLBACK"))
        assertTrue(formatted.contains("Camera Permission: GRANTED"))
        assertTrue(formatted.contains("Rationale: Camera session closed normally."))
        assertTrue(formatted.contains("Evaluation Latency: 0ms"))
    }

    /**
     * Requirement: Copy All with multiple events
     */
    @Test
    fun testFormatHistory_multipleEvents() {
        val event1 = CameraAccessEvent(
            timestamp = fixedTimestamp,
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            cameraId = "0",
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredPackageName = "com.whatsapp",
            packageInferenceConfidence = InferenceConfidence.HIGH,
            inferenceMethod = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            candidateHasCameraPermission = false,
            classification = AccessClassification.UNEXPECTED,
            classificationExplanation = "Permission missing.",
            detectionLatencyMs = 10L
        )

        val event2 = CameraAccessEvent(
            timestamp = fixedTimestamp + 5000L,
            rawEventType = RawCameraEventType.CAMERA_BECAME_AVAILABLE,
            cameraId = "0",
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredPackageName = "com.whatsapp",
            packageInferenceConfidence = InferenceConfidence.HIGH,
            inferenceMethod = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            candidateHasCameraPermission = false,
            classification = AccessClassification.EXPECTED,
            classificationExplanation = "Camera closed.",
            detectionLatencyMs = 5L
        )

        val historyText = HistoryEventFormatter.formatHistory(listOf(event1, event2), testDateFormat)

        assertTrue(historyText.startsWith("=== CameraGuard Event History (2 events) ==="))
        assertTrue(historyText.contains("----------------------------------------"))
        assertTrue(historyText.contains("Classification: UNEXPECTED"))
        assertTrue(historyText.contains("Classification: EXPECTED"))
        assertTrue(historyText.contains("Inferred App: com.whatsapp"))
    }
}
