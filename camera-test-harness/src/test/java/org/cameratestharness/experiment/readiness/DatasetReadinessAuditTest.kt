package org.cameratestharness.experiment.readiness

import org.cameratestharness.experiment.ExperimentDataExporter
import org.cameratestharness.experiment.ExperimentRecord
import org.cameratestharness.experiment.FeatureDatasetRow
import org.cameratestharness.experiment.FeatureExtractor
import org.cameratestharness.experiment.SessionReconstructor
import org.cameratestharness.experiment.T0ProductionFeatureVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DatasetReadinessAuditTest {

    @Test
    fun `test 1 - audit features covers all 20 features from F01 to F20`() {
        val dummyRows = createMockFeatureRows()
        val featureAudits = DatasetReadinessAuditor.auditFeatures(dummyRows)

        assertEquals(20, featureAudits.size)
        val featureIds = featureAudits.map { it.featureId }
        for (i in 1..20) {
            val expectedId = String.format("F%02d", i)
            assertTrue("Expected feature $expectedId to be audited", featureIds.contains(expectedId))
        }
    }

    @Test
    fun `test 2 - screen state features F01, F02, F03 detected as unpolled unknown constants in harness data`() {
        val dummyRows = createMockFeatureRows()
        val audits = DatasetReadinessAuditor.auditFeatures(dummyRows)

        val f01 = audits.first { it.featureId == "F01" }
        val f02 = audits.first { it.featureId == "F02" }
        val f03 = audits.first { it.featureId == "F03" }

        assertTrue(f01.isConstant)
        assertEquals("UNKNOWN", f01.dominantValue)
        assertEquals(1.0, f01.missingnessRate, 0.001)
        assertEquals("UNPOLLED_IN_HARNESS", f01.readinessStatus)

        assertTrue(f02.isConstant)
        assertEquals("UNKNOWN", f02.dominantValue)

        assertTrue(f03.isConstant)
        assertEquals("UNKNOWN", f03.dominantValue)
    }

    @Test
    fun `test 3 - F04 camera permission enforces strict unprivileged sandbox separation`() {
        val dummyRows = createMockFeatureRows()
        val audits = DatasetReadinessAuditor.auditFeatures(dummyRows)

        val f04 = audits.first { it.featureId == "F04" }
        assertFalse(f04.productionT0Eligible)
        assertEquals("ORACLE_ONLY_RESTRICTED", f04.readinessStatus)
        assertTrue(f04.auditNotes.contains("CRITICAL SANDBOX BOUNDARY"))
    }

    @Test
    fun `test 4 - F05 known camera app detected as zero-variance constant false`() {
        val dummyRows = createMockFeatureRows()
        val audits = DatasetReadinessAuditor.auditFeatures(dummyRows)

        val f05 = audits.first { it.featureId == "F05" }
        assertTrue(f05.isConstant)
        assertEquals("false", f05.dominantValue)
        assertEquals(0.0, f05.missingnessRate, 0.001)
        assertEquals("ZERO_VARIANCE_CONSTANT", f05.readinessStatus)
    }

    @Test
    fun `test 5 - F06, F07, F08, F09 provide non-zero variance and distinguish execution contexts`() {
        val dummyRows = createMockFeatureRows()
        val audits = DatasetReadinessAuditor.auditFeatures(dummyRows)

        val f06 = audits.first { it.featureId == "F06" }
        val f07 = audits.first { it.featureId == "F07" }
        val f08 = audits.first { it.featureId == "F08" }
        val f09 = audits.first { it.featureId == "F09" }

        assertFalse("F06 confidence must have non-zero variance", f06.isConstant)
        assertFalse("F07 method must have non-zero variance", f07.isConstant)
        assertFalse("F08 delta must have non-zero variance", f08.isConstant)
        assertFalse("F09 recent activity count must have non-zero variance", f09.isConstant)

        assertTrue(f06.productionT0Eligible)
        assertTrue(f07.productionT0Eligible)
        assertTrue(f08.productionT0Eligible)
        assertTrue(f09.productionT0Eligible)
    }

    @Test
    fun `test 6 - F10 and F11 detected as near-constant rear camera only`() {
        val dummyRows = createMockFeatureRows()
        val audits = DatasetReadinessAuditor.auditFeatures(dummyRows)

        val f10 = audits.first { it.featureId == "F10" }
        val f11 = audits.first { it.featureId == "F11" }

        assertTrue(f10.productionT0Eligible)
        assertTrue(f11.productionT0Eligible)
        assertTrue(f10.auditNotes.contains("Front camera sessions required"))
        assertTrue(f11.auditNotes.contains("Front camera sessions required"))
    }

    @Test
    fun `test 7 - retrospective features F12 to F20 are rejected from production T0 eligibility`() {
        val dummyRows = createMockFeatureRows()
        val audits = DatasetReadinessAuditor.auditFeatures(dummyRows)

        val retrospectiveAudits = audits.filter { it.featureId in (12..20).map { i -> String.format("F%02d", i) } }
        assertEquals(9, retrospectiveAudits.size)

        for (r in retrospectiveAudits) {
            assertEquals("T2_RETROSPECTIVE", r.temporalWindow)
            assertEquals("RESEARCH_ONLY", r.observability)
            assertFalse("Retrospective feature ${r.featureId} must not be production T0 eligible", r.productionT0Eligible)
            assertEquals("RETROSPECTIVE_RESEARCH_ONLY", r.readinessStatus)
        }
    }

    @Test
    fun `test 8 - candidate feature sets are strictly defined with exact feature counts`() {
        val prodT0 = DatasetReadinessAuditor.getFeaturesForCandidateSet(CandidateFeatureSet.PRODUCTION_T0)
        assertEquals(10, prodT0.size)
        assertFalse(prodT0.contains("F04"))
        assertFalse(prodT0.contains("F12"))

        val prodT0Perm = DatasetReadinessAuditor.getFeaturesForCandidateSet(CandidateFeatureSet.PRODUCTION_T0_WITH_UNVERIFIED_PERMISSION)
        assertEquals(11, prodT0Perm.size)
        assertTrue(prodT0Perm.contains("F04"))
        assertFalse(prodT0Perm.contains("F12"))

        val oracle = DatasetReadinessAuditor.getFeaturesForCandidateSet(CandidateFeatureSet.ORACLE_RESEARCH)
        assertEquals(11, oracle.size)
        assertTrue(oracle.contains("F04"))

        val retro = DatasetReadinessAuditor.getFeaturesForCandidateSet(CandidateFeatureSet.RETROSPECTIVE)
        assertEquals(20, retro.size)
        assertTrue(retro.contains("F20"))
    }

    @Test
    fun `test 9 - target formulation binary hardware acquisition distribution and support`() {
        val dummyRows = createMockFeatureRows()
        val classDist = DatasetReadinessAuditor.auditClassDistributions(dummyRows)

        val acqDist = classDist.firstOrNull { it.targetFormulation == "BINARY_HARDWARE_ACQUISITION" && it.className == "CAMERA_ACQUISITION" }
        val noAcqDist = classDist.firstOrNull { it.targetFormulation == "BINARY_HARDWARE_ACQUISITION" && it.className == "NO_CAMERA_CONTROL" }

        assertNotNull(acqDist)
        assertNotNull(noAcqDist)
        assertEquals(5, acqDist!!.includedCount)
        assertEquals(8, noAcqDist!!.includedCount)
        assertEquals(SupportStatus.MARGINAL, acqDist.supportStatus)
        assertEquals(SupportStatus.ADEQUATE, noAcqDist.supportStatus)
    }

    @Test
    fun `test 10 - target formulation multiclass flags classes with insufficient support (N less than 3)`() {
        val dummyRows = createMockFeatureRows()
        val classDist = DatasetReadinessAuditor.auditClassDistributions(dummyRows)

        val multiclass = classDist.filter { it.targetFormulation == "MULTICLASS" }
        assertEquals(5, multiclass.size)

        val ambiguous = multiclass.first { it.className == "AMBIGUOUS_CONTEXT" }
        assertEquals(1, ambiguous.includedCount)
        assertEquals(SupportStatus.INSUFFICIENT, ambiguous.supportStatus)

        val noCam = multiclass.first { it.className == "NO_CAMERA_ACTIVITY" }
        assertEquals(1, noCam.includedCount)
        assertEquals(SupportStatus.INSUFFICIENT, noCam.supportStatus)

        val bgCont = multiclass.first { it.className == "USER_INITIATED_BACKGROUND_CONTINUATION" }
        assertEquals(2, bgCont.includedCount)
        assertEquals(SupportStatus.INSUFFICIENT, bgCont.supportStatus)

        val permDenied = multiclass.first { it.className == "PERMISSION_DENIED" }
        assertEquals(3, permDenied.includedCount)
        assertEquals(SupportStatus.MARGINAL, permDenied.supportStatus)

        val fg = multiclass.first { it.className == "USER_INITIATED_FOREGROUND" }
        assertEquals(6, fg.includedCount)
        assertEquals(SupportStatus.ADEQUATE, fg.supportStatus)
    }

    @Test
    fun `test 11 - target formulation binary alert reveals zero positive alert samples in current dataset`() {
        val dummyRows = createMockFeatureRows()
        val classDist = DatasetReadinessAuditor.auditClassDistributions(dummyRows)

        val alertRow = classDist.first { it.targetFormulation == "BINARY_OPERATIONAL_ALERT" && it.className == "UNEXPECTED_ALERT" }
        assertEquals(0, alertRow.includedCount)
        assertEquals(0.0, alertRow.includedPct, 0.001)
        assertEquals(SupportStatus.INSUFFICIENT, alertRow.supportStatus)
        assertTrue(alertRow.recommendation.contains("0 covert/malicious executions"))
    }

    @Test
    fun `test 12 - standard 5-fold and 10-fold cross-validation are flagged as infeasible`() {
        val dummyRows = createMockFeatureRows()
        val cv = DatasetReadinessAuditor.evaluateCvFeasibility(dummyRows)

        assertFalse("5-fold CV must be flagged as infeasible", cv.is5FoldFeasible)
        assertFalse("10-fold CV must be flagged as infeasible", cv.is10FoldFeasible)
        assertTrue(cv.infeasibilityReason.contains("AMBIGUOUS_CONTEXT (N=1)"))
    }

    @Test
    fun `test 13 - session grouping is strictly required to prevent intra-session data leakage`() {
        val dummyRows = createMockFeatureRows()
        val cv = DatasetReadinessAuditor.evaluateCvFeasibility(dummyRows)

        assertTrue("Session grouping is mandatory", cv.requiresSessionGrouping)
        assertTrue(cv.infeasibilityReason.contains("Session grouping is mandatory"))
    }

    @Test
    fun `test 14 - maximum defensible folds calculated accurately for binary vs multiclass targets`() {
        val dummyRows = createMockFeatureRows()
        val cv = DatasetReadinessAuditor.evaluateCvFeasibility(dummyRows)

        assertEquals(1, cv.maxDefensibleFoldsMulticlass)
        assertTrue(cv.maxDefensibleFoldsBinary in 2..3)
    }

    @Test
    fun `test 15 - overall dataset readiness determination is CONDITIONALLY READY`() {
        val dummyRows = createMockFeatureRows()
        val report = DatasetReadinessAuditor.auditDataset(dummyRows)

        assertEquals("CONDITIONALLY_READY", report.overallReadinessStatus)
        assertEquals(16, report.totalSessions)
        assertEquals(13, report.includedSessions)
        assertEquals(3, report.excludedSessions)
        assertEquals(6, report.physicalDataExpansionRequirements.size)
    }

    @Test
    fun `test 16 - physical raw dataset audit loads 16 sessions and exports readiness artifacts`() {
        val rawDir = File("../data/raw")
        val fallbackRawDir = File("data/raw")
        val targetRawDir = if (rawDir.exists()) rawDir else fallbackRawDir
        if (!targetRawDir.exists()) return

        val csvFiles = targetRawDir.listFiles { _, name -> name.endsWith(".csv") }?.sortedBy { it.name } ?: return
        val allRecords = mutableListOf<ExperimentRecord>()
        for (f in csvFiles) {
            val records = ExperimentDataExporter.parseCsv(f.readText())
            allRecords.addAll(records)
        }

        val sessions = SessionReconstructor.reconstructSessions(allRecords)
        assertEquals(16, sessions.size)

        val rows = sessions.map { FeatureExtractor.extractFromSession(it) }
        assertEquals(16, rows.size)

        val report = DatasetReadinessAuditor.auditDataset(rows)
        assertEquals("CONDITIONALLY_READY", report.overallReadinessStatus)
        assertEquals(16, report.totalSessions)
        assertEquals(13, report.includedSessions)
        assertEquals(3, report.excludedSessions)

        val outDir = File(targetRawDir.parentFile, "derived/phase4/readiness")
        DatasetReadinessAuditor.exportReadinessArtifacts(report, outDir)

        val readinessCsv = File(outDir, "dataset_readiness.csv")
        val classCsv = File(outDir, "class_distribution.csv")
        val manifestJson = File(outDir, "dataset_readiness_manifest.json")

        assertTrue(readinessCsv.exists())
        assertTrue(classCsv.exists())
        assertTrue(manifestJson.exists())

        val readinessLines = readinessCsv.readLines()
        assertEquals(21, readinessLines.size) // 1 header + 20 features

        val classLines = classCsv.readLines()
        assertTrue(classLines.size >= 8)

        val manifestContent = manifestJson.readText()
        assertTrue(manifestContent.contains("\"overall_readiness_status\": \"CONDITIONALLY_READY\""))
        assertTrue(manifestContent.contains("\"PRODUCTION_T0\""))
        assertTrue(manifestContent.contains("\"RETROSPECTIVE\""))
    }

    private fun createMockFeatureRows(): List<FeatureDatasetRow> {
        val rows = mutableListOf<FeatureDatasetRow>()

        // 6 USER_INITIATED_FOREGROUND (3 with camera open, 3 startup/permission checks)
        // Active camera 1
        rows.add(createMockRow("s1", "NORMAL_FOREGROUND_CAMERA", "USER_INITIATED_FOREGROUND", "INCLUDED", null, "GRANTED", 2, "USAGE_STATS_ACTIVITY_RESUMED", 120.0, 1, "0", "TRUE", 12000L))
        // Active camera 2
        rows.add(createMockRow("s2", "CAMERA_START_STOP", "USER_INITIATED_FOREGROUND", "INCLUDED", null, "GRANTED", 2, "USAGE_STATS_ACTIVITY_RESUMED", 120.0, 1, "0", "TRUE", 16000L))
        // Active camera 3
        rows.add(createMockRow("s3", "CAMERA_SESSION_CLOSED", "USER_INITIATED_FOREGROUND", "INCLUDED", null, "GRANTED", 2, "USAGE_STATS_ACTIVITY_RESUMED", 120.0, 1, "0", "TRUE", 25000L))
        // No active camera (startup check 1)
        rows.add(createMockRow("s4", "NORMAL_FOREGROUND_CAMERA", "USER_INITIATED_FOREGROUND", "INCLUDED", null, "GRANTED", 0, "NONE", -1.0, 0, "UNKNOWN", "UNKNOWN", 0L))
        // No active camera (startup check 2)
        rows.add(createMockRow("s5", "NORMAL_FOREGROUND_CAMERA", "USER_INITIATED_FOREGROUND", "INCLUDED", null, "UNVERIFIED", 0, "NONE", -1.0, 0, "UNKNOWN", "UNKNOWN", 7000L))
        // No active camera (startup check 3)
        rows.add(createMockRow("s6", "NORMAL_FOREGROUND_CAMERA", "USER_INITIATED_FOREGROUND", "INCLUDED", null, "UNVERIFIED", 0, "NONE", -1.0, 0, "UNKNOWN", "UNKNOWN", 9000L))

        // 2 USER_INITIATED_BACKGROUND_CONTINUATION (both active camera)
        rows.add(createMockRow("s7", "BACKGROUND_CAMERA_CONTINUATION", "USER_INITIATED_BACKGROUND_CONTINUATION", "INCLUDED", null, "GRANTED", 1, "USAGE_STATS_ACTIVITY_PAUSED", 120.0, 2, "0", "TRUE", 15000L))
        rows.add(createMockRow("s8", "NORMAL_FOREGROUND_CAMERA", "USER_INITIATED_BACKGROUND_CONTINUATION", "INCLUDED", null, "GRANTED", 1, "USAGE_STATS_ACTIVITY_PAUSED", 120.0, 1, "0", "TRUE", 31000L))

        // 3 PERMISSION_DENIED (INCLUDED)
        rows.add(createMockRow("s9", "PERMISSION_DENIED", "PERMISSION_DENIED", "INCLUDED", null, "DENIED", 0, "NONE", -1.0, 0, "UNKNOWN", "UNKNOWN", 0L))
        rows.add(createMockRow("s10", "PERMISSION_DENIED", "PERMISSION_DENIED", "INCLUDED", null, "DENIED", 0, "NONE", -1.0, 0, "UNKNOWN", "UNKNOWN", 6000L))
        rows.add(createMockRow("s11", "PERMISSION_DENIED", "PERMISSION_DENIED", "INCLUDED", null, "DENIED", 0, "NONE", -1.0, 0, "UNKNOWN", "UNKNOWN", 18000L))

        // 2 PERMISSION_DENIED (EXCLUDED)
        rows.add(createMockRow("s12", "PERMISSION_DENIED", "PERMISSION_DENIED", "EXCLUDED", "Invariant violation", "GRANTED", 0, "NONE", -1.0, 0, "UNKNOWN", "UNKNOWN", 0L))
        rows.add(createMockRow("s13", "PERMISSION_DENIED", "PERMISSION_DENIED", "EXCLUDED", "Invariant violation", "GRANTED", 0, "NONE", -1.0, 0, "UNKNOWN", "UNKNOWN", 18000L))

        // 1 NO_CAMERA_ACTIVITY (INCLUDED)
        rows.add(createMockRow("s14", "PERMISSION_GRANTED_NO_CAMERA", "NO_CAMERA_ACTIVITY", "INCLUDED", null, "GRANTED", 0, "NONE", -1.0, 0, "UNKNOWN", "UNKNOWN", 23000L))

        // 1 AMBIGUOUS_CONTEXT (INCLUDED)
        rows.add(createMockRow("s15", "AMBIGUOUS_CONTEXT", "AMBIGUOUS_CONTEXT", "INCLUDED", null, "DENIED", 0, "NONE", -1.0, 0, "UNKNOWN", "UNKNOWN", 19000L))

        // 1 AMBIGUOUS_CONTEXT (EXCLUDED)
        rows.add(createMockRow("s16", "AMBIGUOUS_CONTEXT", "AMBIGUOUS_CONTEXT", "EXCLUDED", "Pre-3.5.1 invalid opening", "GRANTED", 2, "USAGE_STATS_ACTIVITY_RESUMED", 120.0, 1, "0", "TRUE", 44000L))

        return rows
    }

    private fun createMockRow(
        sessionId: String,
        scenarioId: String,
        gt: String,
        inclusionStatus: String,
        exclusionReason: String?,
        f04Perm: String,
        f06Conf: Int,
        f07Method: String,
        f08Delta: Double,
        f09ActCount: Int,
        f10CamId: String,
        f11IsBack: String,
        f12Dur: Long
    ): FeatureDatasetRow {
        return FeatureDatasetRow(
            sessionId = sessionId,
            auditScenarioId = scenarioId,
            repetition = 1,
            timestampIso = "2026-09-25T20:00:00.000Z",
            datasetVersion = "1.0.0",
            inclusionStatus = inclusionStatus,
            exclusionReason = exclusionReason,
            groundTruthContext = gt,
            t0Features = T0ProductionFeatureVector(
                f01ScreenStateCode = "UNKNOWN",
                f02IsScreenInteractive = "UNKNOWN",
                f03IsDeviceLocked = "UNKNOWN",
                f04HasCameraPermission = f04Perm,
                f05IsKnownCameraApp = false,
                f06PackageInferenceConfidence = f06Conf,
                f07InferenceMethodCode = f07Method,
                f08DeltaResumedToTriggerMs = f08Delta,
                f09RecentActivityCount30s = f09ActCount,
                f10CameraHardwareId = f10CamId,
                f11IsBackCamera = f11IsBack
            ),
            f12SessionDurationMs = f12Dur,
            f13ExplicitUserStartAction = if (f12Dur > 0) "TRUE" else "FALSE",
            f14ExplicitUserStopAction = if (f12Dur > 0) "TRUE" else "FALSE",
            f15InternalHarnessState = "IDLE",
            f16HarnessFgServiceActive = f10CamId != "UNKNOWN",
            f17TimeToSessionCloseMs = f12Dur,
            f18AppVisibilityAtTrigger = if (f10CamId != "UNKNOWN") "UNKNOWN" else "FOREGROUND",
            f19BackgroundedDuringSession = gt == "USER_INITIATED_BACKGROUND_CONTINUATION",
            f20ReturnedToForeground = gt == "USER_INITIATED_BACKGROUND_CONTINUATION"
        )
    }
}
