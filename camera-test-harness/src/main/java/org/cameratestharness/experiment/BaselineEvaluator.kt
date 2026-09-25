package org.cameratestharness.experiment

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scientific baseline evaluation engine for CameraGuard Phase 4.5.
 * Replays reconstructed experimental sessions through Phase 2 rule evaluation,
 * calculating coverage, indeterminate rates, confusion matrices, and scenario-level metrics.
 */
object BaselineEvaluator {

    const val EVALUATION_VERSION = "1.0.0"

    /**
     * Evaluates a single reconstructed session using the Phase 2 rule logic.
     * Note: Ground truth is NEVER passed to the evaluator; it is joined purely for output recording.
     */
    fun evaluateSession(
        session: ReconstructedSession,
        strictProductionObservability: Boolean = true
    ): BaselinePredictionRecord {
        val hasCameraAccess = session.cameraLifecycle == ReconstructedSession.CameraLifecycleState.OPENED_AND_CLOSED ||
                session.cameraLifecycle == ReconstructedSession.CameraLifecycleState.OPENED_UNCLOSED

        if (!hasCameraAccess) {
            // Negative control / no-camera scenario
            return BaselinePredictionRecord(
                sessionId = session.sessionId,
                auditScenarioId = session.primaryScenarioId,
                repetition = session.repetition,
                groundTruthContext = session.groundTruthContext,
                actualCameraActivity = false,
                cameraLifecycle = session.cameraLifecycle.name,
                baselineClassification = "NO_CAMERA_EVENT",
                baselineExplanation = "No camera acquisition occurred on device; CameraManager did not dispatch availability transition.",
                ruleTriggered = "NO_CAMERA_ACTIVITY",
                inferredPackageName = session.inferredPackageName.ifBlank { "NONE" },
                packageConfidence = "NONE",
                inferenceMethod = "NONE",
                screenState = session.screenState.ifBlank { "UNKNOWN" },
                permissionState = session.permissionState.ifBlank { "UNKNOWN" },
                evaluationSubset = "NO_CAMERA_CONTROL",
                isDecisive = true,
                inclusionStatus = if (session.isExcludedFromMl) "EXCLUDED" else "INCLUDED",
                exclusionReason = session.exclusionReason
            )
        }

        // Camera activation occurred: evaluate at trigger point
        val screen = when (session.screenState.uppercase()) {
            "ON_UNLOCKED", "ON" -> Phase2RuleAdapter.ScreenState.SCREEN_ON_UNLOCKED
            "ON_LOCKED" -> Phase2RuleAdapter.ScreenState.SCREEN_ON_LOCKED
            "OFF" -> Phase2RuleAdapter.ScreenState.SCREEN_OFF
            else -> Phase2RuleAdapter.ScreenState.SCREEN_ON_UNLOCKED // ScreenTracker default when unpolled
        }

        val confidence = when {
            session.triggerMechanism == "AUTOMATED_TIMER" -> Phase2RuleAdapter.Confidence.LOW
            session.appVisibility == "BACKGROUND" -> Phase2RuleAdapter.Confidence.LOW
            session.recentUserInteraction == true -> Phase2RuleAdapter.Confidence.MEDIUM
            else -> Phase2RuleAdapter.Confidence.LOW
        }

        val method = when {
            session.activityLifecycle in listOf("STOPPED", "PAUSED") || session.appVisibility == "BACKGROUND" ->
                Phase2RuleAdapter.Method.USAGE_STATS_ACTIVITY_PAUSED
            else ->
                Phase2RuleAdapter.Method.USAGE_STATS_ACTIVITY_RESUMED
        }

        val perm = if (strictProductionObservability) {
            null // Unprivileged sandbox cannot query third-party runtime permissions
        } else {
            when (session.permissionState) {
                "GRANTED" -> true
                "DENIED" -> false
                else -> null
            }
        }

        val inferredContext = Phase2RuleAdapter.InferredContext(
            packageName = session.inferredPackageName.ifBlank { null },
            confidence = confidence,
            method = method,
            hasCameraPermission = perm,
            deltaFromEventMs = if (confidence == Phase2RuleAdapter.Confidence.LOW) 12000L else 120L
        )

        val result = Phase2RuleAdapter.evaluate(
            rawEventType = Phase2RuleAdapter.RawEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = screen,
            inferredContext = inferredContext
        )

        return BaselinePredictionRecord(
            sessionId = session.sessionId,
            auditScenarioId = session.primaryScenarioId,
            repetition = session.repetition,
            groundTruthContext = session.groundTruthContext,
            actualCameraActivity = true,
            cameraLifecycle = session.cameraLifecycle.name,
            baselineClassification = result.classification.name,
            baselineExplanation = result.explanation,
            ruleTriggered = result.ruleTriggered,
            inferredPackageName = inferredContext.packageName,
            packageConfidence = confidence.name,
            inferenceMethod = method.name,
            screenState = screen.name,
            permissionState = if (perm == null) "UNVERIFIED" else if (perm) "GRANTED" else "DENIED",
            evaluationSubset = "CAMERA_ACTIVATION",
            isDecisive = result.classification != Phase2RuleAdapter.Classification.UNKNOWN,
            inclusionStatus = if (session.isExcludedFromMl) "EXCLUDED" else "INCLUDED",
            exclusionReason = session.exclusionReason
        )
    }

    /**
     * Evaluates a collection of ReconstructedSession instances deterministically.
     */
    fun evaluateAll(
        sessions: List<ReconstructedSession>,
        strictProductionObservability: Boolean = true
    ): List<BaselinePredictionRecord> {
        val sortedSessions = sessions.sortedBy { it.sessionId }
        return sortedSessions.map { session ->
            evaluateSession(session, strictProductionObservability)
        }
    }

    data class ScenarioStats(
        val scenarioId: String,
        val totalSessions: Int,
        val cameraAcquisitions: Int,
        val expectedCount: Int,
        val unexpectedCount: Int,
        val unknownCount: Int,
        val noCameraCount: Int,
        val decisiveCount: Int
    )

    data class BaselineMetrics(
        val totalSessions: Int,
        val includedSessions: Int,
        val excludedSessions: Int,
        val cameraActivationSessions: Int,
        val noCameraControlSessions: Int,
        val overallExpectedCount: Int,
        val overallUnexpectedCount: Int,
        val overallUnknownCount: Int,
        val overallNoCameraEventCount: Int,
        val activationDecisiveCount: Int,
        val activationUnknownCount: Int,
        val activationCoverageRate: Double,
        val activationIndeterminateRate: Double,
        val scenarioBreakdown: Map<String, ScenarioStats>,
        // Operational Alert Policy (Alert = UNEXPECTED vs Non-Alert = EXPECTED, UNKNOWN, NO_CAMERA_EVENT)
        val alertCount: Int,
        val nonAlertCount: Int,
        val alertRate: Double
    )

    /**
     * Computes comprehensive evaluation metrics over baseline prediction records.
     */
    fun computeMetrics(records: List<BaselinePredictionRecord>): BaselineMetrics {
        val included = records.filter { it.inclusionStatus == "INCLUDED" }
        val excluded = records.filter { it.inclusionStatus == "EXCLUDED" }

        val activationSessions = included.filter { it.evaluationSubset == "CAMERA_ACTIVATION" }
        val noCameraSessions = included.filter { it.evaluationSubset == "NO_CAMERA_CONTROL" }

        val expCount = included.count { it.baselineClassification == "EXPECTED" }
        val unexpCount = included.count { it.baselineClassification == "UNEXPECTED" }
        val unkCount = included.count { it.baselineClassification == "UNKNOWN" }
        val noCamCount = included.count { it.baselineClassification == "NO_CAMERA_EVENT" }

        val actDecisive = activationSessions.count { it.isDecisive }
        val actUnknown = activationSessions.count { !it.isDecisive }

        val coverageRate = if (activationSessions.isNotEmpty()) {
            actDecisive.toDouble() / activationSessions.size
        } else {
            0.0
        }

        val indetRate = if (activationSessions.isNotEmpty()) {
            actUnknown.toDouble() / activationSessions.size
        } else {
            0.0
        }

        // Scenario breakdown
        val scenarioMap = mutableMapOf<String, ScenarioStats>()
        for ((scenarioId, group) in included.groupBy { it.auditScenarioId }) {
            scenarioMap[scenarioId] = ScenarioStats(
                scenarioId = scenarioId,
                totalSessions = group.size,
                cameraAcquisitions = group.count { it.actualCameraActivity },
                expectedCount = group.count { it.baselineClassification == "EXPECTED" },
                unexpectedCount = group.count { it.baselineClassification == "UNEXPECTED" },
                unknownCount = group.count { it.baselineClassification == "UNKNOWN" },
                noCameraCount = group.count { it.baselineClassification == "NO_CAMERA_EVENT" },
                decisiveCount = group.count { it.isDecisive }
            )
        }

        val alertCount = unexpCount
        val nonAlertCount = expCount + unkCount + noCamCount
        val alertRate = if (included.isNotEmpty()) alertCount.toDouble() / included.size else 0.0

        return BaselineMetrics(
            totalSessions = records.size,
            includedSessions = included.size,
            excludedSessions = excluded.size,
            cameraActivationSessions = activationSessions.size,
            noCameraControlSessions = noCameraSessions.size,
            overallExpectedCount = expCount,
            overallUnexpectedCount = unexpCount,
            overallUnknownCount = unkCount,
            overallNoCameraEventCount = noCamCount,
            activationDecisiveCount = actDecisive,
            activationUnknownCount = actUnknown,
            activationCoverageRate = coverageRate,
            activationIndeterminateRate = indetRate,
            scenarioBreakdown = scenarioMap,
            alertCount = alertCount,
            nonAlertCount = nonAlertCount,
            alertRate = alertRate
        )
    }

    /**
     * Generates a multiclass confusion matrix (Predicted Classification vs Ground Truth Context).
     */
    fun generateConfusionMatrix(records: List<BaselinePredictionRecord>): Map<String, Map<String, Int>> {
        val included = records.filter { it.inclusionStatus == "INCLUDED" }
        val matrix = mutableMapOf<String, MutableMap<String, Int>>()

        val classifications = listOf("EXPECTED", "UNEXPECTED", "UNKNOWN", "NO_CAMERA_EVENT")
        val groundTruths = included.map { it.groundTruthContext }.distinct().sorted()

        for (gt in groundTruths) {
            val row = mutableMapOf<String, Int>()
            for (c in classifications) {
                row[c] = 0
            }
            matrix[gt] = row
        }

        for (r in included) {
            val row = matrix.getOrPut(r.groundTruthContext) { mutableMapOf() }
            row[r.baselineClassification] = (row[r.baselineClassification] ?: 0) + 1
        }

        return matrix
    }

    /**
     * Exports evaluation results to data/derived/phase4/baseline/
     */
    fun exportEvaluationResults(
        records: List<BaselinePredictionRecord>,
        outputDir: File
    ) {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        // 1. baseline_predictions.csv
        val predLines = mutableListOf<String>()
        predLines.add(BaselinePredictionRecord.CSV_HEADER)
        for (r in records) {
            predLines.add(r.toCsvRow())
        }
        File(outputDir, "baseline_predictions.csv").writeText(predLines.joinToString("\n"))

        // 2. baseline_metrics.csv
        val metrics = computeMetrics(records)
        val metricLines = mutableListOf<String>()
        metricLines.add("metric_name,value,denominator,percentage_or_rate,description")
        metricLines.add("total_sessions,${metrics.totalSessions},${metrics.totalSessions},100.0%,\"Total reconstructed sessions ingested\"")
        metricLines.add("included_sessions,${metrics.includedSessions},${metrics.totalSessions},${String.format(Locale.US, "%.1f", metrics.includedSessions.toDouble() / metrics.totalSessions * 100)}%,\"Sessions meeting ML & evaluation criteria\"")
        metricLines.add("excluded_sessions,${metrics.excludedSessions},${metrics.totalSessions},${String.format(Locale.US, "%.1f", metrics.excludedSessions.toDouble() / metrics.totalSessions * 100)}%,\"Sessions excluded due to legacy contradictions\"")
        metricLines.add("camera_activation_sessions,${metrics.cameraActivationSessions},${metrics.includedSessions},${String.format(Locale.US, "%.1f", metrics.cameraActivationSessions.toDouble() / metrics.includedSessions * 100)}%,\"Sessions with active physical camera acquisition\"")
        metricLines.add("no_camera_control_sessions,${metrics.noCameraControlSessions},${metrics.includedSessions},${String.format(Locale.US, "%.1f", metrics.noCameraControlSessions.toDouble() / metrics.includedSessions * 100)}%,\"Negative control sessions without camera acquisition\"")
        metricLines.add("activation_decisive_count,${metrics.activationDecisiveCount},${metrics.cameraActivationSessions},${String.format(Locale.US, "%.1f", metrics.activationCoverageRate * 100)}%,\"Decisive classifications (EXPECTED/UNEXPECTED)\"")
        metricLines.add("activation_unknown_count,${metrics.activationUnknownCount},${metrics.cameraActivationSessions},${String.format(Locale.US, "%.1f", metrics.activationIndeterminateRate * 100)}%,\"Indeterminate telemetry resulting in UNKNOWN\"")
        metricLines.add("overall_expected_count,${metrics.overallExpectedCount},${metrics.includedSessions},${String.format(Locale.US, "%.1f", metrics.overallExpectedCount.toDouble() / metrics.includedSessions * 100)}%,\"Classified as EXPECTED\"")
        metricLines.add("overall_unexpected_count,${metrics.overallUnexpectedCount},${metrics.includedSessions},${String.format(Locale.US, "%.1f", metrics.overallUnexpectedCount.toDouble() / metrics.includedSessions * 100)}%,\"Classified as UNEXPECTED (Alerts)\"")
        metricLines.add("overall_unknown_count,${metrics.overallUnknownCount},${metrics.includedSessions},${String.format(Locale.US, "%.1f", metrics.overallUnknownCount.toDouble() / metrics.includedSessions * 100)}%,\"Classified as UNKNOWN\"")
        metricLines.add("overall_no_camera_count,${metrics.overallNoCameraEventCount},${metrics.includedSessions},${String.format(Locale.US, "%.1f", metrics.overallNoCameraEventCount.toDouble() / metrics.includedSessions * 100)}%,\"No camera hardware event callback received\"")
        metricLines.add("operational_alert_rate,${metrics.alertCount},${metrics.includedSessions},${String.format(Locale.US, "%.1f", metrics.alertRate * 100)}%,\"Percentage of sessions triggering an active user alert\"")
        File(outputDir, "baseline_metrics.csv").writeText(metricLines.joinToString("\n"))

        // 3. baseline_confusion_matrix.csv
        val matrix = generateConfusionMatrix(records)
        val matrixLines = mutableListOf<String>()
        matrixLines.add("ground_truth_context,pred_EXPECTED,pred_UNEXPECTED,pred_UNKNOWN,pred_NO_CAMERA_EVENT,total_support")
        for ((gt, row) in matrix) {
            val exp = row["EXPECTED"] ?: 0
            val unexp = row["UNEXPECTED"] ?: 0
            val unk = row["UNKNOWN"] ?: 0
            val noCam = row["NO_CAMERA_EVENT"] ?: 0
            val total = exp + unexp + unk + noCam
            matrixLines.add("$gt,$exp,$unexp,$unk,$noCam,$total")
        }
        File(outputDir, "baseline_confusion_matrix.csv").writeText(matrixLines.joinToString("\n"))

        // 4. baseline_manifest.json
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        val exclusions = records.filter { it.inclusionStatus == "EXCLUDED" }
            .associate { it.sessionId to (it.exclusionReason ?: "UNKNOWN") }

        val jsonLines = mutableListOf<String>()
        jsonLines.add("{")
        jsonLines.add("  \"evaluation_version\": \"$EVALUATION_VERSION\",")
        jsonLines.add("  \"evaluator\": \"Phase2RuleAdapter (org.cameraguard.monitoring.detection.CameraRuleEvaluator)\",")
        jsonLines.add("  \"timestamp\": \"${sdf.format(Date())}\",")
        jsonLines.add("  \"total_sessions\": ${metrics.totalSessions},")
        jsonLines.add("  \"included_sessions\": ${metrics.includedSessions},")
        jsonLines.add("  \"excluded_sessions\": ${metrics.excludedSessions},")
        jsonLines.add("  \"camera_activation_sessions\": ${metrics.cameraActivationSessions},")
        jsonLines.add("  \"no_camera_control_sessions\": ${metrics.noCameraControlSessions},")
        jsonLines.add("  \"activation_coverage_rate\": ${String.format(Locale.US, "%.4f", metrics.activationCoverageRate)},")
        jsonLines.add("  \"activation_indeterminate_rate\": ${String.format(Locale.US, "%.4f", metrics.activationIndeterminateRate)},")
        jsonLines.add("  \"operational_alert_rate\": ${String.format(Locale.US, "%.4f", metrics.alertRate)},")
        jsonLines.add("  \"scenario_breakdown\": {")
        val scenEntries = metrics.scenarioBreakdown.entries.map { e ->
            val s = e.value
            "    \"${e.key}\": { \"total\": ${s.totalSessions}, \"camera_acquisitions\": ${s.cameraAcquisitions}, \"expected\": ${s.expectedCount}, \"unexpected\": ${s.unexpectedCount}, \"unknown\": ${s.unknownCount}, \"no_camera\": ${s.noCameraCount} }"
        }
        jsonLines.add(scenEntries.joinToString(",\n"))
        jsonLines.add("  },")
        jsonLines.add("  \"exclusions\": {")
        val exclEntries = exclusions.entries.map { "    \"${it.key}\": \"${it.value.replace("\"", "\\\"")}\"" }
        jsonLines.add(exclEntries.joinToString(",\n"))
        jsonLines.add("  }")
        jsonLines.add("}")
        File(outputDir, "baseline_manifest.json").writeText(jsonLines.joinToString("\n"))
    }
}
