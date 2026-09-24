package org.cameraguard.monitoring.detection

import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.data.model.ScreenInteractivityState
import org.cameraguard.monitoring.telemetry.InferredPackageContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CameraRuleEvaluatorTest {

    private lateinit var evaluator: CameraRuleEvaluator

    @Before
    fun setup() {
        evaluator = CameraRuleEvaluator()
    }

    @Test
    fun testCameraBecameAvailable_isAlwaysExpected() {
        val result = evaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_AVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_OFF,
            inferredContext = InferredPackageContext(
                packageName = null,
                confidence = InferenceConfidence.NONE,
                method = InferenceMethod.NONE,
                hasCameraPermission = null,
                deltaFromEventMs = null
            )
        )
        assertEquals(AccessClassification.EXPECTED, result.classification)
        assertTrue(result.explanation.contains("available state"))
    }

    @Test
    fun testCameraUnavailable_whenScreenOff_isUnexpected() {
        val result = evaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_OFF,
            inferredContext = InferredPackageContext(
                packageName = "com.suspicious.app",
                confidence = InferenceConfidence.HIGH,
                method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
                hasCameraPermission = true,
                deltaFromEventMs = 100L
            )
        )
        assertEquals(AccessClassification.UNEXPECTED, result.classification)
        assertTrue(result.explanation.contains("screen was OFF"))
    }

    @Test
    fun testCameraUnavailable_whenDeviceLocked_isUnexpected() {
        val result = evaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_LOCKED,
            inferredContext = InferredPackageContext(
                packageName = "com.some.app",
                confidence = InferenceConfidence.MEDIUM,
                method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
                hasCameraPermission = true,
                deltaFromEventMs = 400L
            )
        )
        assertEquals(AccessClassification.UNEXPECTED, result.classification)
        assertTrue(result.explanation.contains("device was locked"))
    }

    @Test
    fun testCameraUnavailable_whenCandidateLacksPermission_isUnexpected() {
        val result = evaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = InferredPackageContext(
                packageName = "com.unauthorized.tool",
                confidence = InferenceConfidence.HIGH,
                method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
                hasCameraPermission = false,
                deltaFromEventMs = 150L
            )
        )
        assertEquals(AccessClassification.UNEXPECTED, result.classification)
        assertTrue(result.explanation.contains("does not hold android.permission.CAMERA"))
    }

    @Test
    fun testCameraUnavailable_whenScreenOnAndCandidateHasPermission_isExpected() {
        val result = evaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = InferredPackageContext(
                packageName = "com.google.android.GoogleCamera",
                confidence = InferenceConfidence.HIGH,
                method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
                hasCameraPermission = true,
                deltaFromEventMs = 80L
            )
        )
        assertEquals(AccessClassification.EXPECTED, result.classification)
        assertTrue(result.explanation.contains("holds CAMERA permission"))
    }

    @Test
    fun testCameraUnavailable_whenNoAppCorrelated_isUnknown() {
        val result = evaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = InferredPackageContext(
                packageName = null,
                confidence = InferenceConfidence.NONE,
                method = InferenceMethod.NONE,
                hasCameraPermission = null,
                deltaFromEventMs = null
            )
        )
        assertEquals(AccessClassification.UNKNOWN, result.classification)
        assertTrue(result.explanation.contains("Inconclusive telemetry"))
    }

    @Test
    fun testCameraUnavailable_whenPermissionUnverified_isUnknown() {
        val result = evaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = InferredPackageContext(
                packageName = "com.whatsapp",
                confidence = InferenceConfidence.HIGH,
                method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
                hasCameraPermission = null,
                deltaFromEventMs = 120L
            )
        )
        assertEquals(AccessClassification.UNKNOWN, result.classification)
        assertTrue(result.explanation.contains("Camera permission could not be verified from sandbox"))
    }
}
