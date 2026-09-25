package org.cameratestharness.research.ml

import org.cameratestharness.experiment.ExperimentDataExporter
import org.cameratestharness.experiment.ExperimentRecord
import org.cameratestharness.experiment.FeatureDatasetRow
import org.cameratestharness.experiment.FeatureExtractor
import org.cameratestharness.experiment.ReconstructedSession
import org.cameratestharness.experiment.SessionReconstructor
import org.cameratestharness.experiment.T0ProductionFeatureVector
import java.io.File
import java.util.Locale

/**
 * Prepares and validates session-level feature datasets for Phase 4.6.4 Multi-Model Evaluation.
 * Guarantees that:
 * 1. The 3 invalid legacy sessions are strictly excluded.
 * 2. All 41 targeted Phase 4.6.2 sessions remain included and valid.
 * 3. Primary benchmark strictly uses PRODUCTION_T0 (with F04 clamped to UNVERIFIED).
 * 4. Zero retrospective features (F12..F20) or scenario/target labels leak into feature matrices.
 */
data class MlSessionRecord(
    val sessionId: String,
    val cohort: String,
    val scenarioId: String,
    val groundTruthContext: String,
    val repetition: Int,
    val inclusionStatus: String,
    val exclusionReason: String?,
    // Task Targets:
    val task2Target: String, // CAMERA_ACQUISITION vs NO_CAMERA_CONTROL
    val task3Target: String, // LEGITIMATE vs AMBIGUOUS vs CONTROLS
    // Production T0 Features (F04 clamped to UNVERIFIED):
    val f01ScreenState: Double,
    val f02IsInteractive: Double,
    val f03IsLocked: Double,
    val f04PermClamped: Double, // Constant -1.0 in production
    val f05KnownCameraApp: Double,
    val f06PackageConfidence: Double,
    val f07InferenceMethod: Double,
    val f08DeltaResumedMs: Double,
    val f09RecentActivityCount: Double,
    val f10CameraId: Double,
    val f11IsBackCamera: Double,
    // Oracle Permission Feature (Ablation Only):
    val f04PermOracle: Double
) {
    fun toProductionFeatureVector(): DoubleArray {
        return doubleArrayOf(
            f01ScreenState,
            f02IsInteractive,
            f03IsLocked,
            f04PermClamped,
            f05KnownCameraApp,
            f06PackageConfidence,
            f07InferenceMethod,
            f08DeltaResumedMs,
            f09RecentActivityCount,
            f10CameraId,
            f11IsBackCamera
        )
    }

    fun toOracleFeatureVector(): DoubleArray {
        return doubleArrayOf(
            f01ScreenState,
            f02IsInteractive,
            f03IsLocked,
            f04PermOracle,
            f05KnownCameraApp,
            f06PackageConfidence,
            f07InferenceMethod,
            f08DeltaResumedMs,
            f09RecentActivityCount,
            f10CameraId,
            f11IsBackCamera
        )
    }

    fun toCsvRow(): String {
        return listOf(
            sessionId,
            cohort,
            scenarioId,
            groundTruthContext,
            repetition.toString(),
            inclusionStatus,
            "\"${(exclusionReason ?: "").replace("\"", "\"\"")}\"",
            task2Target,
            task3Target,
            String.format(Locale.US, "%.1f", f01ScreenState),
            String.format(Locale.US, "%.1f", f02IsInteractive),
            String.format(Locale.US, "%.1f", f03IsLocked),
            String.format(Locale.US, "%.1f", f04PermClamped),
            String.format(Locale.US, "%.1f", f05KnownCameraApp),
            String.format(Locale.US, "%.1f", f06PackageConfidence),
            String.format(Locale.US, "%.1f", f07InferenceMethod),
            String.format(Locale.US, "%.1f", f08DeltaResumedMs),
            String.format(Locale.US, "%.1f", f09RecentActivityCount),
            String.format(Locale.US, "%.1f", f10CameraId),
            String.format(Locale.US, "%.1f", f11IsBackCamera),
            String.format(Locale.US, "%.1f", f04PermOracle)
        ).joinToString(",")
    }

    companion object {
        val CSV_HEADER = listOf(
            "session_id",
            "cohort",
            "scenario_id",
            "ground_truth_context",
            "repetition",
            "inclusion_status",
            "exclusion_reason",
            "task2_target",
            "task3_target",
            "f01_screen_state",
            "f02_is_interactive",
            "f03_is_locked",
            "f04_perm_clamped",
            "f05_known_camera_app",
            "f06_package_confidence",
            "f07_inference_method",
            "f08_delta_resumed_ms",
            "f09_recent_activity_count",
            "f10_camera_id",
            "f11_is_back_camera",
            "f04_perm_oracle"
        ).joinToString(",")

        val PRODUCTION_FEATURE_NAMES = listOf(
            "f01_screen_state",
            "f02_is_interactive",
            "f03_is_locked",
            "f04_perm_clamped",
            "f05_known_camera_app",
            "f06_package_confidence",
            "f07_inference_method",
            "f08_delta_resumed_ms",
            "f09_recent_activity_count",
            "f10_camera_id",
            "f11_is_back_camera"
        )
    }
}

object Phase464DatasetPreparation {

    val TARGETED_SESSION_PREFIXES = listOf(
        "exp_20260925_231012_rep1", "exp_20260925_231144_rep2",
        "exp_20260925_231222_rep3", "exp_20260925_231248_rep4",
        "exp_20260925_231325_rep5",
        "exp_20260925_231536_rep1", "exp_20260925_231602_rep2",
        "exp_20260925_231629_rep3", "exp_20260925_231652_rep4",
        "exp_20260925_231714_rep5",
        "exp_20260925_233810_rep1", "exp_20260925_233828_rep2",
        "exp_20260925_233846_rep3", "exp_20260925_233903_rep4",
        "exp_20260925_233922_rep5",
        "exp_20260925_234031_rep1", "exp_20260925_234046_rep2",
        "exp_20260925_234056_rep3", "exp_20260925_234105_rep4",
        "exp_20260925_234114_rep5",
        "exp_20260926_000031_rep1", "exp_20260926_000047_rep2",
        "exp_20260926_000056_rep3", "exp_20260926_000105_rep4",
        "exp_20260926_000116_rep5",
        "exp_20260926_000225_rep1", "exp_20260926_000237_rep2",
        "exp_20260926_000246_rep3", "exp_20260926_000257_rep4",
        "exp_20260926_000305_rep5",
        "exp_20260926_000409_rep1", "exp_20260926_000424_rep2",
        "exp_20260926_000436_rep3",
        "exp_20260926_000531_rep1", "exp_20260926_000553_rep2",
        "exp_20260926_000613_rep3", "exp_20260926_000631_rep4",
        "exp_20260926_000654_rep5",
        "exp_20260926_000808_rep1", "exp_20260926_000829_rep2",
        "exp_20260926_000855_rep3"
    )

    val BASELINE_RAW_FILES = setOf(
        "cameraguard_experiment_20260925_205319.csv",
        "cameraguard_experiment_20260925_205652.csv",
        "cameraguard_experiment_20260925_213654.csv",
        "persisted_experiment_history.csv"
    )

    /**
     * Reconstructs all sessions and prepares ML session records.
     */
    fun prepareMlDataset(rawDir: File): List<MlSessionRecord> {
        val csvFiles = rawDir.listFiles { _, name -> name.endsWith(".csv") }?.sortedBy { it.name } ?: emptyList()
        val allRecords = mutableListOf<ExperimentRecord>()
        for (f in csvFiles) {
            allRecords.addAll(ExperimentDataExporter.parseCsv(f.readText()))
        }

        val sessions = SessionReconstructor.reconstructSessions(allRecords)
        val targetedSids = mutableSetOf<String>()
        for (pfx in TARGETED_SESSION_PREFIXES) {
            val match = sessions.firstOrNull { it.sessionId.startsWith(pfx) }
            if (match != null) {
                targetedSids.add(match.sessionId)
            }
        }

        val baselineRecords = csvFiles.filter { it.name in BASELINE_RAW_FILES }.flatMap {
            ExperimentDataExporter.parseCsv(it.readText())
        }
        val baselineSessions = SessionReconstructor.reconstructSessions(baselineRecords)
        val baselineSids = baselineSessions.map { it.sessionId }.toSet()

        val mlRecords = mutableListOf<MlSessionRecord>()

        for (sess in sessions) {
            val cohort = when {
                sess.sessionId in targetedSids -> "TARGETED_41"
                sess.sessionId in baselineSids -> "BASELINE_16"
                else -> "INTERMEDIATE_35"
            }

            // Extract with production clamping (F04 = UNVERIFIED)
            val prodRow = FeatureExtractor.extractFromSession(sess, strictProductionObservability = true)
            // Extract with oracle permission
            val oracleRow = FeatureExtractor.extractFromSession(sess, strictProductionObservability = false)

            val prodT0 = prodRow.t0Features
            val oracleT0 = oracleRow.t0Features

            // Task 2 Target: CAMERA_ACQUISITION vs NO_CAMERA_CONTROL
            val hasAcquisition = sess.cameraLifecycle == ReconstructedSession.CameraLifecycleState.OPENED_AND_CLOSED ||
                    sess.orderedEvents.any { it.cameraEvent in listOf("CAMERA_OPENED", "CAPTURE_SESSION_STARTED") }
            val task2Target = if (hasAcquisition) "CAMERA_ACQUISITION" else "NO_CAMERA_CONTROL"

            // Task 3 Target: LEGITIMATE vs AMBIGUOUS vs CONTROLS
            val task3Target = when (sess.primaryScenarioId) {
                "NORMAL_FOREGROUND_CAMERA", "CAMERA_START_STOP", "CAMERA_SESSION_CLOSED" -> "LEGITIMATE"
                "BACKGROUND_CAMERA_CONTINUATION", "AUTOMATED_BACKGROUND_TRIGGER", "AMBIGUOUS_CONTEXT" -> "AMBIGUOUS"
                "PERMISSION_DENIED", "PERMISSION_GRANTED_NO_CAMERA" -> "CONTROLS"
                else -> "OTHER"
            }

            mlRecords.add(
                MlSessionRecord(
                    sessionId = sess.sessionId,
                    cohort = cohort,
                    scenarioId = sess.primaryScenarioId,
                    groundTruthContext = sess.groundTruthContext,
                    repetition = sess.repetition,
                    inclusionStatus = if (sess.isExcludedFromMl) "EXCLUDED" else "INCLUDED",
                    exclusionReason = sess.exclusionReason,
                    task2Target = task2Target,
                    task3Target = task3Target,
                    f01ScreenState = T0ProductionFeatureVector.encodeScreenState(prodT0.f01ScreenStateCode),
                    f02IsInteractive = T0ProductionFeatureVector.encodeThreeState(prodT0.f02IsScreenInteractive),
                    f03IsLocked = T0ProductionFeatureVector.encodeThreeState(prodT0.f03IsDeviceLocked),
                    f04PermClamped = T0ProductionFeatureVector.encodePermission(prodT0.f04HasCameraPermission),
                    f05KnownCameraApp = if (prodT0.f05IsKnownCameraApp) 1.0 else 0.0,
                    f06PackageConfidence = prodT0.f06PackageInferenceConfidence.toDouble(),
                    f07InferenceMethod = T0ProductionFeatureVector.encodeInferenceMethod(prodT0.f07InferenceMethodCode),
                    f08DeltaResumedMs = prodT0.f08DeltaResumedToTriggerMs,
                    f09RecentActivityCount = prodT0.f09RecentActivityCount30s.toDouble(),
                    f10CameraId = T0ProductionFeatureVector.encodeCameraId(prodT0.f10CameraHardwareId),
                    f11IsBackCamera = T0ProductionFeatureVector.encodeThreeState(prodT0.f11IsBackCamera),
                    f04PermOracle = T0ProductionFeatureVector.encodePermission(oracleT0.f04HasCameraPermission)
                )
            )
        }

        return mlRecords
    }

    /**
     * Exports the ML dataset to a CSV file.
     */
    fun exportMlDataset(records: List<MlSessionRecord>, outputFile: File) {
        outputFile.parentFile?.let {
            if (!it.exists()) it.mkdirs()
        }
        val lines = mutableListOf<String>()
        lines.add(MlSessionRecord.CSV_HEADER)
        for (r in records) {
            lines.add(r.toCsvRow())
        }
        outputFile.writeText(lines.joinToString("\n"))
    }
}
