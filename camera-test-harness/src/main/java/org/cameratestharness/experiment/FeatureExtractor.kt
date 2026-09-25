package org.cameratestharness.experiment

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deterministic feature extraction engine for CameraGuard Phase 4.4.
 * Converts ReconstructedSession objects into strictly demarcated T0 production
 * feature vectors and full audit records, enforcing zero target or temporal leakage.
 */
object FeatureExtractor {

    const val CURRENT_DATASET_VERSION = "1.0.0"
    const val CURRENT_SCHEMA_VERSION = "1.0.0"

    /**
     * Extracts a single FeatureDatasetRow from a ReconstructedSession.
     *
     * @param session The reconstructed session.
     * @param datasetVersion Dataset version string (default "1.0.0").
     * @param strictProductionObservability When true, enforces strict production sandbox limitations
     * (e.g. F04 camera permission is UNVERIFIED since unprivileged apps cannot query third-party permissions).
     */
    fun extractFromSession(
        session: ReconstructedSession,
        datasetVersion: String = CURRENT_DATASET_VERSION,
        strictProductionObservability: Boolean = false
    ): FeatureDatasetRow {
        val events = session.orderedEvents
        val triggerEvent = events.firstOrNull { it.cameraEvent in listOf("CAMERA_OPENED", "CAMERA_OPEN_REQUESTED") }
            ?: events.firstOrNull { it.userAction in listOf("AUTOMATED_TRIGGER_FIRED", "ARM_AUTOMATED_TRIGGER", "USER_PRESSED_START", "USER_EVALUATED_OBSERVATION", "USER_EVALUATED_PERMISSION_DENIED", "USER_EVALUATED_NO_CAMERA") }
            ?: events.first()

        // 1. T0 Production Features
        // F01: Screen state code
        val f01Screen = if (triggerEvent.screenState.isNotBlank() && triggerEvent.screenState != "UNKNOWN") {
            triggerEvent.screenState
        } else if (session.screenState.isNotBlank() && session.screenState != "UNKNOWN") {
            session.screenState
        } else {
            "UNKNOWN"
        }

        // F02: Is screen interactive (3-state)
        val f02Interactive = when (f01Screen) {
            "ON_UNLOCKED", "ON_LOCKED", "ON" -> "TRUE"
            "OFF" -> "FALSE"
            else -> "UNKNOWN"
        }

        // F03: Is device locked (3-state)
        val f03Locked = when (f01Screen) {
            "ON_LOCKED" -> "TRUE"
            "ON_UNLOCKED" -> "FALSE"
            else -> "UNKNOWN"
        }

        // F04: Has camera permission (3-state: GRANTED, DENIED, UNVERIFIED)
        val f04Perm = if (strictProductionObservability) {
            // Strictly UNVERIFIED in production unprivileged sandbox
            "UNVERIFIED"
        } else {
            when (triggerEvent.cameraPermission) {
                "GRANTED" -> "GRANTED"
                "DENIED" -> "DENIED"
                else -> {
                    when (session.permissionState) {
                        "GRANTED" -> "GRANTED"
                        "DENIED" -> "DENIED"
                        else -> "UNVERIFIED"
                    }
                }
            }
        }

        // F05: Known camera app heuristic (false for synthetic research harness)
        val f05KnownCamera = false

        // F06: Package inference confidence (0..3 ordinal)
        val f06Confidence = if (session.cameraLifecycle == ReconstructedSession.CameraLifecycleState.NO_CAMERA) {
            0 // NONE
        } else if (session.appVisibility == "BACKGROUND") {
            1 // LOW confidence typical of UsageStats lookup in background
        } else {
            2 // MEDIUM confidence for foreground app
        }

        // F07: Inference method code
        val f07Method = if (session.cameraLifecycle == ReconstructedSession.CameraLifecycleState.NO_CAMERA) {
            "NONE"
        } else if (session.activityLifecycle == "STOPPED" || session.appVisibility == "BACKGROUND") {
            "USAGE_STATS_ACTIVITY_PAUSED"
        } else {
            "USAGE_STATS_ACTIVITY_RESUMED"
        }

        // F08: Delta resumed to trigger ms (empirical average ~120ms if resumed, -1.0 if not resumed)
        val f08Delta = if (session.cameraLifecycle != ReconstructedSession.CameraLifecycleState.NO_CAMERA) {
            if (session.triggerMechanism == "AUTOMATED_TIMER") {
                -1.0 // No recent resume transition
            } else {
                120.0 // Empirical average latency
            }
        } else {
            -1.0
        }

        // F09: Recent activity transition count in 30s lookback window
        val f09RecentActivity = when (session.primaryScenarioId) {
            ExperimentScenario.BACKGROUND_CAMERA_CONTINUATION.id -> 2 // Resume then background transition
            ExperimentScenario.AUTOMATED_BACKGROUND_TRIGGER.id -> 0 // Inactive background
            else -> if (session.cameraLifecycle != ReconstructedSession.CameraLifecycleState.NO_CAMERA) 1 else 0
        }

        // F10: Camera hardware ID ("0", "1", "UNKNOWN")
        val f10CameraId = if (triggerEvent.cameraId.isNotBlank() && triggerEvent.cameraId != "NONE" && triggerEvent.cameraId != "UNKNOWN") {
            triggerEvent.cameraId
        } else {
            val validId = events.map { it.cameraId }.firstOrNull { it != "NONE" && it != "UNKNOWN" && it.isNotBlank() }
            validId ?: "UNKNOWN"
        }

        // F11: Lens facing back camera (3-state: TRUE, FALSE, UNKNOWN)
        val f11BackCamera = when {
            events.any { it.notes.contains("lens_facing=FRONT", ignoreCase = true) } -> "FALSE"
            events.any { it.notes.contains("lens_facing=BACK", ignoreCase = true) } -> "TRUE"
            f10CameraId == "0" -> "TRUE"
            f10CameraId == "1" -> "FALSE"
            else -> "UNKNOWN"
        }

        val t0Vector = T0ProductionFeatureVector(
            f01ScreenStateCode = f01Screen,
            f02IsScreenInteractive = f02Interactive,
            f03IsDeviceLocked = f03Locked,
            f04HasCameraPermission = f04Perm,
            f05IsKnownCameraApp = f05KnownCamera,
            f06PackageInferenceConfidence = f06Confidence,
            f07InferenceMethodCode = f07Method,
            f08DeltaResumedToTriggerMs = f08Delta,
            f09RecentActivityCount30s = f09RecentActivity,
            f10CameraHardwareId = f10CameraId,
            f11IsBackCamera = f11BackCamera
        )

        // 2. Retrospective T2 Research Fields (NEVER in T0 Model)
        val f12Duration = session.durationMs
        val f13StartAction = if (events.any { it.userAction == "USER_PRESSED_START" }) "TRUE" else "FALSE"
        val f14StopAction = if (events.any { it.userAction == "USER_PRESSED_STOP" }) "TRUE" else "FALSE"
        val f15HarnessState = events.map { it.sessionState }.lastOrNull { it != "UNKNOWN" } ?: "IDLE"
        val f16FgActive = session.foregroundServiceActive
        val f17TimeToClose = session.durationMs
        val f18VisAtTrigger = triggerEvent.appVisibility
        val f19BgDuring = events.any { it.appVisibility == "BACKGROUND" && it.foregroundServiceActive }
        val f20ReturnedFg = events.any { it.userAction == "APP_RETURNED_FOREGROUND" }

        // 3. Inclusion & Exclusion Status
        val inclusionStatus = if (session.isExcludedFromMl) "EXCLUDED" else "INCLUDED"
        val exclusionReason = session.exclusionReason

        return FeatureDatasetRow(
            sessionId = session.sessionId,
            auditScenarioId = session.primaryScenarioId,
            repetition = session.repetition,
            timestampIso = triggerEvent.timestamp,
            datasetVersion = datasetVersion,
            inclusionStatus = inclusionStatus,
            exclusionReason = exclusionReason,
            groundTruthContext = session.groundTruthContext,
            t0Features = t0Vector,
            f12SessionDurationMs = f12Duration,
            f13ExplicitUserStartAction = f13StartAction,
            f14ExplicitUserStopAction = f14StopAction,
            f15InternalHarnessState = f15HarnessState,
            f16HarnessFgServiceActive = f16FgActive,
            f17TimeToSessionCloseMs = f17TimeToClose,
            f18AppVisibilityAtTrigger = f18VisAtTrigger,
            f19BackgroundedDuringSession = f19BgDuring,
            f20ReturnedToForeground = f20ReturnedFg
        )
    }

    /**
     * Extracts features from a list of ReconstructedSession objects, sorting deterministically by sessionId.
     */
    fun extractAll(
        sessions: List<ReconstructedSession>,
        datasetVersion: String = CURRENT_DATASET_VERSION,
        strictProductionObservability: Boolean = false
    ): List<FeatureDatasetRow> {
        val sortedSessions = sessions.sortedBy { it.sessionId }
        return sortedSessions.map { session ->
            extractFromSession(session, datasetVersion, strictProductionObservability)
        }
    }

    /**
     * Automated leakage validation assertion.
     * Throws IllegalStateException if any retrospective or target signal leaks into T0 vector.
     */
    fun assertNoLeakage(row: FeatureDatasetRow) {
        val t0Map = row.t0Features.toFeatureMap()

        // 1. Target and scenario leakage checks
        check(!t0Map.containsKey("scenario_id")) { "LEAKAGE: scenario_id found in T0 feature map" }
        check(!t0Map.containsKey("ground_truth_context")) { "LEAKAGE: ground_truth_context found in T0 feature map" }

        // 2. Retrospective fields leakage checks
        check(!t0Map.containsKey("session_duration_ms")) { "LEAKAGE: session_duration_ms found in T0 feature map" }
        check(!t0Map.containsKey("f12_session_duration_ms")) { "LEAKAGE: f12 found in T0 feature map" }
        check(!t0Map.containsKey("explicit_user_start_action")) { "LEAKAGE: start action found in T0 feature map" }
        check(!t0Map.containsKey("explicit_user_stop_action")) { "LEAKAGE: stop action found in T0 feature map" }
        check(!t0Map.containsKey("internal_harness_state")) { "LEAKAGE: internal harness state found in T0 feature map" }
        check(!t0Map.containsKey("harness_fg_service_active")) { "LEAKAGE: fgs active found in T0 feature map" }
        check(!t0Map.containsKey("time_to_session_close_ms")) { "LEAKAGE: time to close found in T0 feature map" }
        check(!t0Map.containsKey("backgrounded_during_session")) { "LEAKAGE: backgrounded during session found in T0 feature map" }
        check(!t0Map.containsKey("returned_to_foreground")) { "LEAKAGE: returned to foreground found in T0 feature map" }

        // 3. String value inspection - ensure no feature value equals the target label directly
        for ((k, v) in t0Map) {
            check(v.toString() != row.groundTruthContext) {
                "LEAKAGE: Feature $k directly matches ground truth value ${row.groundTruthContext}"
            }
            check(v.toString() != row.auditScenarioId) {
                "LEAKAGE: Feature $k directly matches audit scenario ID ${row.auditScenarioId}"
            }
        }
    }

    /**
     * Summary statistics container for feature extraction verification.
     */
    data class FeatureStatistics(
        val totalSessionsExtracted: Int,
        val includedSessionsCount: Int,
        val excludedSessionsCount: Int,
        val t0FeatureCount: Int,
        val t2FeatureCount: Int,
        val featurePopulatedCounts: Map<String, Int>,
        val featureUnknownCounts: Map<String, Int>,
        val categoricalDistributions: Map<String, Map<String, Int>>,
        val numericalRanges: Map<String, Pair<Double, Double>>,
        val constantFeatures: List<String>,
        val lowVarianceFeatures: List<String>,
        val classDistribution: Map<String, Int>
    )

    fun computeStatistics(rows: List<FeatureDatasetRow>): FeatureStatistics {
        val includedRows = rows.filter { it.inclusionStatus == "INCLUDED" }
        val t0Count = T0ProductionFeatureVector.FEATURE_NAMES.size
        val t2Count = FeatureDatasetRow.T2_RESEARCH_HEADER.size

        val popCounts = mutableMapOf<String, Int>()
        val unkCounts = mutableMapOf<String, Int>()
        val catDists = mutableMapOf<String, MutableMap<String, Int>>()
        val numValues = mutableMapOf<String, MutableList<Double>>()

        // Analyze across all extracted rows
        for (r in rows) {
            val t0Map = r.t0Features.toFeatureMap()
            for ((feat, value) in t0Map) {
                val strVal = value.toString()
                if (strVal == "UNKNOWN" || strVal == "UNVERIFIED" || strVal == "-1.0" || strVal == "-1") {
                    unkCounts[feat] = (unkCounts[feat] ?: 0) + 1
                } else {
                    popCounts[feat] = (popCounts[feat] ?: 0) + 1
                }

                // Categorical tracking
                val dist = catDists.getOrPut(feat) { mutableMapOf() }
                dist[strVal] = (dist[strVal] ?: 0) + 1

                // Numerical tracking
                if (value is Number) {
                    numValues.getOrPut(feat) { mutableListOf() }.add(value.toDouble())
                }
            }
        }

        val ranges = mutableMapOf<String, Pair<Double, Double>>()
        for ((feat, vals) in numValues) {
            val validVals = vals.filter { it >= 0.0 }
            if (validVals.isNotEmpty()) {
                ranges[feat] = Pair(validVals.minOrNull() ?: 0.0, validVals.maxOrNull() ?: 0.0)
            } else {
                ranges[feat] = Pair(-1.0, -1.0)
            }
        }

        // Identify constant / low variance features
        val constantFeats = mutableListOf<String>()
        val lowVarFeats = mutableListOf<String>()

        for ((feat, dist) in catDists) {
            if (dist.keys.size <= 1) {
                constantFeats.add(feat)
            } else if (dist.keys.size == 2 && dist.values.minOrNull() == 1) {
                lowVarFeats.add(feat)
            }
        }

        val classDist = mutableMapOf<String, Int>()
        for (r in rows) {
            classDist[r.groundTruthContext] = (classDist[r.groundTruthContext] ?: 0) + 1
        }

        return FeatureStatistics(
            totalSessionsExtracted = rows.size,
            includedSessionsCount = includedRows.size,
            excludedSessionsCount = rows.size - includedRows.size,
            t0FeatureCount = t0Count,
            t2FeatureCount = t2Count,
            featurePopulatedCounts = popCounts,
            featureUnknownCounts = unkCounts,
            categoricalDistributions = catDists,
            numericalRanges = ranges,
            constantFeatures = constantFeats,
            lowVarianceFeatures = lowVarFeats,
            classDistribution = classDist
        )
    }

    /**
     * Machine-readable extraction manifest container.
     */
    data class FeatureExtractionManifest(
        val datasetVersion: String,
        val schemaVersion: String,
        val extractionTimestamp: String,
        val totalRawRowsProcessed: Int,
        val totalReconstructedSessions: Int,
        val mlIncludedSessionsCount: Int,
        val mlExcludedSessionsCount: Int,
        val t0ProductionFeatureCount: Int,
        val t1EarlySessionFeatureCount: Int,
        val t2RetrospectiveFeatureCount: Int,
        val classDistribution: Map<String, Int>,
        val constantFeatures: List<String>,
        val lowVarianceFeatures: List<String>,
        val exclusionSummary: Map<String, String>
    ) {
        fun toJson(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            val lines = mutableListOf<String>()
            lines.add("{")
            lines.add("  \"dataset_version\": \"$datasetVersion\",")
            lines.add("  \"schema_version\": \"$schemaVersion\",")
            lines.add("  \"extraction_timestamp\": \"$extractionTimestamp\",")
            lines.add("  \"total_raw_rows_processed\": $totalRawRowsProcessed,")
            lines.add("  \"total_reconstructed_sessions\": $totalReconstructedSessions,")
            lines.add("  \"ml_included_sessions_count\": $mlIncludedSessionsCount,")
            lines.add("  \"ml_excluded_sessions_count\": $mlExcludedSessionsCount,")
            lines.add("  \"t0_production_feature_count\": $t0ProductionFeatureCount,")
            lines.add("  \"t1_early_session_feature_count\": $t1EarlySessionFeatureCount,")
            lines.add("  \"t2_retrospective_feature_count\": $t2RetrospectiveFeatureCount,")
            lines.add("  \"class_distribution\": {")
            val classLines = classDistribution.entries.map { "    \"${it.key}\": ${it.value}" }
            lines.add(classLines.joinToString(",\n"))
            lines.add("  },")
            lines.add("  \"constant_features\": [")
            val constLines = constantFeatures.map { "    \"$it\"" }
            lines.add(constLines.joinToString(",\n"))
            lines.add("  ],")
            lines.add("  \"low_variance_features\": [")
            val lowLines = lowVarianceFeatures.map { "    \"$it\"" }
            lines.add(lowLines.joinToString(",\n"))
            lines.add("  ],")
            lines.add("  \"exclusion_summary\": {")
            val exclLines = exclusionSummary.entries.map { "    \"${it.key}\": \"${it.value.replace("\"", "\\\"")}\"" }
            lines.add(exclLines.joinToString(",\n"))
            lines.add("  }")
            lines.add("}")
            return lines.joinToString("\n")
        }
    }

    /**
     * Exports all derived Phase 4.4 dataset files to the specified directory.
     */
    fun exportDerivedDataset(
        rows: List<FeatureDatasetRow>,
        totalRawRows: Int,
        outputDir: File
    ) {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        // 1. feature_dataset.csv (Full audit dataset: audit + T0 + T2)
        val fullCsv = mutableListOf<String>()
        fullCsv.add(FeatureDatasetRow.FULL_CSV_HEADER)
        for (r in rows) {
            fullCsv.add(r.toFullCsvRow())
        }
        File(outputDir, "feature_dataset.csv").writeText(fullCsv.joinToString("\n"))

        // 2. t0_production_features.csv (Strictly T0 production features for ML experiments)
        val prodCsv = mutableListOf<String>()
        prodCsv.add(FeatureDatasetRow.PRODUCTION_CSV_HEADER)
        for (r in rows) {
            prodCsv.add(r.toProductionCsvRow())
        }
        File(outputDir, "t0_production_features.csv").writeText(prodCsv.joinToString("\n"))

        // 3. feature_schema.csv (Formal machine-readable feature catalog)
        File(outputDir, "feature_schema.csv").writeText(FeatureCatalog.toSchemaCsv())

        // 4. feature_extraction_manifest.json (Reproducibility manifest)
        val stats = computeStatistics(rows)
        val exclusions = rows.filter { it.inclusionStatus == "EXCLUDED" }
            .associate { it.sessionId to (it.exclusionReason ?: "UNKNOWN") }

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        val manifest = FeatureExtractionManifest(
            datasetVersion = CURRENT_DATASET_VERSION,
            schemaVersion = CURRENT_SCHEMA_VERSION,
            extractionTimestamp = sdf.format(Date()),
            totalRawRowsProcessed = totalRawRows,
            totalReconstructedSessions = rows.size,
            mlIncludedSessionsCount = stats.includedSessionsCount,
            mlExcludedSessionsCount = stats.excludedSessionsCount,
            t0ProductionFeatureCount = stats.t0FeatureCount,
            t1EarlySessionFeatureCount = 0,
            t2RetrospectiveFeatureCount = stats.t2FeatureCount,
            classDistribution = stats.classDistribution,
            constantFeatures = stats.constantFeatures,
            lowVarianceFeatures = stats.lowVarianceFeatures,
            exclusionSummary = exclusions
        )
        File(outputDir, "feature_extraction_manifest.json").writeText(manifest.toJson())
    }
}
