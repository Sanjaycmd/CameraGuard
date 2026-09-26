package org.cameratestharness.research.adversarial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdversaryEvaluationTest {

    @Test
    fun testScenarioIdParsing() {
        assertEquals(ScenarioId.A1, ScenarioId.fromRaw("A1"))
        assertEquals(ScenarioId.A1, ScenarioId.fromRaw("A1_FOREGROUND"))
        assertEquals(ScenarioId.A2, ScenarioId.fromRaw("A2"))
        assertEquals(ScenarioId.A2, ScenarioId.fromRaw("A2_BACKGROUND"))
        assertEquals(ScenarioId.A3, ScenarioId.fromRaw("A3"))
        assertEquals(ScenarioId.A4, ScenarioId.fromRaw("A4"))
        assertEquals(ScenarioId.A5, ScenarioId.fromRaw("A5"))
        assertEquals(ScenarioId.A6, ScenarioId.fromRaw("A6"))
        assertEquals(ScenarioId.A6, ScenarioId.fromRaw("A6_burst_1"))
        assertNull(ScenarioId.fromRaw("UNKNOWN_SCENARIO"))
    }

    @Test
    fun testAccessResultClassification_success() {
        assertEquals(AccessGroundTruth.SUCCESS, AdversaryMetricsCalculator.parseAccessResult("SUCCESS"))
        assertEquals(AccessGroundTruth.SUCCESS, AdversaryMetricsCalculator.parseAccessResult("0"))
        assertEquals(AccessGroundTruth.SUCCESS, AdversaryMetricsCalculator.parseAccessResult("CameraDevice opened successfully"))
    }

    @Test
    fun testAccessResultClassification_blockedByPlatform() {
        assertEquals(
            AccessGroundTruth.BLOCKED_BY_PLATFORM,
            AdversaryMetricsCalculator.parseAccessResult("ERROR_CAMERA_DISABLED (Blocked by platform policy)")
        )
        assertEquals(
            AccessGroundTruth.BLOCKED_BY_PLATFORM,
            AdversaryMetricsCalculator.parseAccessResult("SecurityException: Background camera access disallowed")
        )
        assertEquals(
            AccessGroundTruth.BLOCKED_BY_PLATFORM,
            AdversaryMetricsCalculator.parseAccessResult("BLOCKED_BY_PLATFORM")
        )
    }

    @Test
    fun testAccessResultClassification_deniedAndFailed() {
        assertEquals(
            AccessGroundTruth.DENIED,
            AdversaryMetricsCalculator.parseAccessResult("PERMISSION_DENIED")
        )
        assertEquals(
            AccessGroundTruth.FAILED,
            AdversaryMetricsCalculator.parseAccessResult("ERROR_CAMERA_IN_USE")
        )
        assertEquals(
            AccessGroundTruth.FAILED,
            AdversaryMetricsCalculator.parseAccessResult("ERROR_CAMERA_DEVICE")
        )
        assertEquals(
            AccessGroundTruth.UNKNOWN,
            AdversaryMetricsCalculator.parseAccessResult("unrecognized string")
        )
    }

    @Test
    fun testPrivacyIndicatorStateParsing() {
        assertEquals(
            PrivacyIndicatorObservation.VISIBLE,
            AdversaryMetricsCalculator.parseIndicatorState("VISIBLE")
        )
        assertEquals(
            PrivacyIndicatorObservation.NOT_OBSERVED,
            AdversaryMetricsCalculator.parseIndicatorState("NOT_OBSERVED")
        )
        assertEquals(
            PrivacyIndicatorObservation.NOT_APPLICABLE,
            AdversaryMetricsCalculator.parseIndicatorState("NOT_APPLICABLE")
        )
        assertEquals(
            PrivacyIndicatorObservation.UNKNOWN,
            AdversaryMetricsCalculator.parseIndicatorState("SOME_UNKNOWN_VALUE")
        )
    }

    @Test
    fun testCameraGuardDetectionComparison_successfulDetection() {
        val execution = AdversaryScenarioExecution(
            scenarioId = ScenarioId.A1,
            description = "Foreground intentional control",
            accessGroundTruth = AccessGroundTruth.SUCCESS,
            privacyIndicator = PrivacyIndicatorObservation.VISIBLE,
            cameraManagerEventDetected = true,
            cameraGuardDetected = true,
            attributedPackage = "org.cameraguard.adversarytest",
            expectedPackage = "org.cameraguard.adversarytest",
            classification = "UNEXPECTED",
            expectedClassification = "UNEXPECTED",
            latencyMs = 12L
        )

        assertTrue(execution.isSuccessfulAccess)
        assertFalse(execution.isPlatformBlocked)
        assertTrue(execution.isAttributionCorrect)
    }

    @Test
    fun testCameraGuardDetectionComparison_platformBlockedIsNotDetectionFailure() {
        val execution = AdversaryScenarioExecution(
            scenarioId = ScenarioId.A2,
            description = "Background camera access attempt",
            accessGroundTruth = AccessGroundTruth.BLOCKED_BY_PLATFORM,
            privacyIndicator = PrivacyIndicatorObservation.NOT_APPLICABLE,
            cameraManagerEventDetected = false,
            cameraGuardDetected = false,
            attributedPackage = null,
            expectedPackage = "org.cameraguard.adversarytest",
            classification = null,
            expectedClassification = "UNEXPECTED"
        )

        assertFalse(execution.isSuccessfulAccess)
        assertTrue(execution.isPlatformBlocked)
        assertFalse(execution.isAttributionCorrect)
    }

    @Test
    fun testMetricCalculation_perfectDetectionWithBlockedScenarios() {
        val executions = listOf(
            AdversaryScenarioExecution(
                scenarioId = ScenarioId.A1,
                description = "Foreground",
                accessGroundTruth = AccessGroundTruth.SUCCESS,
                privacyIndicator = PrivacyIndicatorObservation.VISIBLE,
                cameraManagerEventDetected = true,
                cameraGuardDetected = true,
                attributedPackage = "org.cameraguard.adversarytest",
                classification = "UNEXPECTED"
            ),
            AdversaryScenarioExecution(
                scenarioId = ScenarioId.A2,
                description = "Background",
                accessGroundTruth = AccessGroundTruth.BLOCKED_BY_PLATFORM,
                privacyIndicator = PrivacyIndicatorObservation.NOT_APPLICABLE,
                cameraManagerEventDetected = false,
                cameraGuardDetected = false,
                attributedPackage = null,
                classification = null
            ),
            AdversaryScenarioExecution(
                scenarioId = ScenarioId.A3,
                description = "Post-foreground",
                accessGroundTruth = AccessGroundTruth.BLOCKED_BY_PLATFORM,
                privacyIndicator = PrivacyIndicatorObservation.NOT_APPLICABLE,
                cameraManagerEventDetected = false,
                cameraGuardDetected = false,
                attributedPackage = null,
                classification = null
            ),
            AdversaryScenarioExecution(
                scenarioId = ScenarioId.A4,
                description = "Service",
                accessGroundTruth = AccessGroundTruth.SUCCESS,
                privacyIndicator = PrivacyIndicatorObservation.VISIBLE,
                cameraManagerEventDetected = true,
                cameraGuardDetected = true,
                attributedPackage = "org.cameraguard.adversarytest",
                classification = "UNEXPECTED"
            ),
            AdversaryScenarioExecution(
                scenarioId = ScenarioId.A5,
                description = "Locked",
                accessGroundTruth = AccessGroundTruth.BLOCKED_BY_PLATFORM,
                privacyIndicator = PrivacyIndicatorObservation.NOT_APPLICABLE,
                cameraManagerEventDetected = false,
                cameraGuardDetected = false,
                attributedPackage = null,
                classification = null
            ),
            AdversaryScenarioExecution(
                scenarioId = ScenarioId.A6,
                description = "Rapid burst",
                accessGroundTruth = AccessGroundTruth.SUCCESS,
                privacyIndicator = PrivacyIndicatorObservation.VISIBLE,
                cameraManagerEventDetected = true,
                cameraGuardDetected = true,
                attributedPackage = "org.cameraguard.adversarytest",
                classification = "UNEXPECTED"
            )
        )

        val summary = AdversaryMetricsCalculator.calculateMetrics(executions)

        assertEquals(6, summary.totalScenarios)
        assertEquals(3, summary.successfulAccesses)
        assertEquals(3, summary.platformBlockedAttempts)
        assertEquals(3, summary.cameraGuardDetections)
        assertEquals(0, summary.missedAccesses)
        assertEquals(0, summary.falsePositives)
        assertEquals(0, summary.attributionErrors)
        assertEquals(0, summary.classificationErrors)
        assertEquals(0, summary.indicatorAbsentSuccessfulAccesses)

        assertEquals(1.0, summary.detectionRate, 0.0001)
        assertEquals(0.0, summary.missRate, 0.0001)
        assertEquals(0.0, summary.attributionErrorRate, 0.0001)
        assertEquals(0.0, summary.classificationErrorRate, 0.0001)

        // Indicator-absent accesses: 0, must report "N/A — no successful indicator-absent camera access was reproduced."
        assertEquals(
            "N/A — no successful indicator-absent camera access was reproduced.",
            summary.indicatorIndependentDetectionRateString
        )
    }

    @Test
    fun testMetricCalculation_handlesIndicatorAbsentWhenPresent() {
        val executions = listOf(
            AdversaryScenarioExecution(
                scenarioId = ScenarioId.A1,
                description = "Foreground",
                accessGroundTruth = AccessGroundTruth.SUCCESS,
                privacyIndicator = PrivacyIndicatorObservation.NOT_OBSERVED,
                cameraManagerEventDetected = true,
                cameraGuardDetected = true,
                attributedPackage = "org.cameraguard.adversarytest",
                classification = "UNEXPECTED"
            )
        )

        val summary = AdversaryMetricsCalculator.calculateMetrics(executions)
        assertEquals(1, summary.indicatorAbsentSuccessfulAccesses)
        assertEquals("100.0% (1/1)", summary.indicatorIndependentDetectionRateString)
    }
}
