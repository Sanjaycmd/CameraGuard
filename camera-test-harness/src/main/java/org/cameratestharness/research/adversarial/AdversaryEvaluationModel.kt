package org.cameratestharness.research.adversarial

import java.util.Locale

enum class AccessGroundTruth {
    SUCCESS,
    DENIED,
    BLOCKED_BY_PLATFORM,
    FAILED,
    UNKNOWN;

    companion object {
        fun fromString(value: String): AccessGroundTruth =
            entries.find { it.name.equals(value.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

enum class PrivacyIndicatorObservation {
    VISIBLE,
    NOT_OBSERVED,
    NOT_APPLICABLE,
    UNKNOWN;

    companion object {
        fun fromString(value: String): PrivacyIndicatorObservation =
            entries.find { it.name.equals(value.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

enum class ScenarioId(val rawId: String) {
    A1("A1"),
    A2("A2"),
    A3("A3"),
    A4("A4"),
    A5("A5"),
    A6("A6");

    companion object {
        fun fromRaw(raw: String): ScenarioId? {
            val upper = raw.trim().uppercase()
            return entries.find { it.rawId == upper || upper.startsWith(it.rawId) }
        }
    }
}

data class AdversaryScenarioExecution(
    val scenarioId: ScenarioId,
    val description: String,
    val accessGroundTruth: AccessGroundTruth,
    val privacyIndicator: PrivacyIndicatorObservation,
    val cameraManagerEventDetected: Boolean,
    val cameraGuardDetected: Boolean,
    val attributedPackage: String?,
    val expectedPackage: String = "org.cameraguard.adversarytest",
    val classification: String?,
    val expectedClassification: String = "UNEXPECTED",
    val latencyMs: Long? = null,
    val detail: String = ""
) {
    val isSuccessfulAccess: Boolean
        get() = accessGroundTruth == AccessGroundTruth.SUCCESS

    val isPlatformBlocked: Boolean
        get() = accessGroundTruth == AccessGroundTruth.BLOCKED_BY_PLATFORM

    val isAttributionCorrect: Boolean
        get() = cameraGuardDetected && attributedPackage == expectedPackage
}

data class AdversaryEvaluationSummary(
    val totalScenarios: Int,
    val successfulAccesses: Int,
    val platformBlockedAttempts: Int,
    val cameraGuardDetections: Int,
    val missedAccesses: Int,
    val falsePositives: Int,
    val attributionErrors: Int,
    val classificationErrors: Int,
    val indicatorAbsentSuccessfulAccesses: Int,
    val detectionRate: Double,
    val missRate: Double,
    val attributionErrorRate: Double,
    val classificationErrorRate: Double,
    val indicatorIndependentDetectionRateString: String
)

object AdversaryMetricsCalculator {

    fun calculateMetrics(executions: List<AdversaryScenarioExecution>): AdversaryEvaluationSummary {
        val total = executions.size
        val successful = executions.filter { it.isSuccessfulAccess }
        val platformBlocked = executions.count { it.isPlatformBlocked }
        val successfulCount = successful.size

        val detections = successful.count { it.cameraGuardDetected }
        val missed = successful.count { !it.cameraGuardDetected }

        // False positives: CameraGuard detected an event when access was NOT successful
        val falsePositives = executions.count { !it.isSuccessfulAccess && it.cameraGuardDetected }

        // Attribution errors among detected successful accesses
        val detectedSuccesses = successful.filter { it.cameraGuardDetected }
        val attributionErrors = detectedSuccesses.count { it.attributedPackage != it.expectedPackage }

        // Classification errors among evaluable events
        val classificationErrors = detectedSuccesses.count {
            it.classification != null && it.classification != it.expectedClassification
        }

        val detectionRate = if (successfulCount > 0) detections.toDouble() / successfulCount else 0.0
        val missRate = if (successfulCount > 0) missed.toDouble() / successfulCount else 0.0
        val attributionErrorRate = if (detectedSuccesses.isNotEmpty()) attributionErrors.toDouble() / detectedSuccesses.size else 0.0
        val classificationErrorRate = if (detectedSuccesses.isNotEmpty()) classificationErrors.toDouble() / detectedSuccesses.size else 0.0

        // Indicator-independent detection
        val indicatorAbsentSuccessful = successful.filter { it.privacyIndicator == PrivacyIndicatorObservation.NOT_OBSERVED }
        val indicatorAbsentCount = indicatorAbsentSuccessful.size
        val indicatorIndependentString = if (indicatorAbsentCount == 0) {
            "N/A — no successful indicator-absent camera access was reproduced."
        } else {
            val detectedWithoutIndicator = indicatorAbsentSuccessful.count { it.cameraGuardDetected }
            val rate = (detectedWithoutIndicator.toDouble() / indicatorAbsentCount) * 100.0
            String.format(Locale.US, "%.1f%% (%d/%d)", rate, detectedWithoutIndicator, indicatorAbsentCount)
        }

        return AdversaryEvaluationSummary(
            totalScenarios = total,
            successfulAccesses = successfulCount,
            platformBlockedAttempts = platformBlocked,
            cameraGuardDetections = detections,
            missedAccesses = missed,
            falsePositives = falsePositives,
            attributionErrors = attributionErrors,
            classificationErrors = classificationErrors,
            indicatorAbsentSuccessfulAccesses = indicatorAbsentCount,
            detectionRate = detectionRate,
            missRate = missRate,
            attributionErrorRate = attributionErrorRate,
            classificationErrorRate = classificationErrorRate,
            indicatorIndependentDetectionRateString = indicatorIndependentString
        )
    }

    fun parseAccessResult(errorCodeOrStatus: String): AccessGroundTruth {
        val trimmed = errorCodeOrStatus.trim()
        return when {
            trimmed.contains("SUCCESS", ignoreCase = true) || trimmed == "0" -> AccessGroundTruth.SUCCESS
            trimmed.contains("DISABLED", ignoreCase = true) ||
            trimmed.contains("BLOCKED", ignoreCase = true) ||
            trimmed.contains("POLICY", ignoreCase = true) ||
            trimmed.contains("SecurityException", ignoreCase = true) -> AccessGroundTruth.BLOCKED_BY_PLATFORM
            trimmed.contains("DENIED", ignoreCase = true) ||
            trimmed.contains("PERMISSION", ignoreCase = true) -> AccessGroundTruth.DENIED
            trimmed.contains("FAIL", ignoreCase = true) ||
            trimmed.contains("ERROR", ignoreCase = true) -> AccessGroundTruth.FAILED
            else -> AccessGroundTruth.UNKNOWN
        }
    }

    fun parseIndicatorState(state: String): PrivacyIndicatorObservation {
        return PrivacyIndicatorObservation.fromString(state)
    }
}
