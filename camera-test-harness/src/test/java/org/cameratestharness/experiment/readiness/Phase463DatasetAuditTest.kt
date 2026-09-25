package org.cameratestharness.experiment.readiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit and integration tests for Phase 4.6.3 Dataset Re-Audit and ML Readiness Analysis.
 */
class Phase463DatasetAuditTest {

    private fun getTargetRawDir(): File? {
        val rawDir = File("../data/raw")
        val fallbackRawDir = File("data/raw")
        val targetRawDir = if (rawDir.exists()) rawDir else fallbackRawDir
        return if (targetRawDir.exists()) targetRawDir else null
    }

    @Test
    fun `test 01 - raw file inventory discovers 15 CSV files and calculates correct counts`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        val inv = auditResult.inventory
        assertEquals(15, inv.totalFiles)
        assertEquals(1193, inv.totalRawRecords)
        assertEquals(652, inv.uniqueEventRecords)
        assertEquals(541, inv.duplicateRecordCount)
        assertEquals(92, inv.totalSessions)
        assertEquals(41, inv.multiFileSessions)
        assertEquals(51, inv.singleFileSessions)
        assertEquals(4, inv.baselineFiles.size)
        assertEquals(11, inv.expansionFiles.size)
    }

    @Test
    fun `test 02 - deduplication analysis detects exact duplicate export rows`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        assertTrue("Exact duplicates must be detected from multi-session dumps", auditResult.inventory.duplicateRecordCount > 500)
        assertEquals(1193 - 652, auditResult.inventory.duplicateRecordCount)
    }

    @Test
    fun `test 03 - session reconstructor reconstructs all 92 sessions across 15 files`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        assertEquals(92, auditResult.sessionDetails.size)
        val includedCount = auditResult.sessionDetails.count { it.inclusionStatus == "INCLUDED" }
        val excludedCount = auditResult.sessionDetails.count { it.inclusionStatus == "EXCLUDED" }
        assertEquals(89, includedCount)
        assertEquals(3, excludedCount) // Exactly the 3 legacy Phase 4.2 sessions
    }

    @Test
    fun `test 04 - targeted cohort extraction matches exactly 41 physical sessions`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        assertEquals(41, auditResult.targetedSids.size)
        val targetedDetails = auditResult.sessionDetails.filter { it.cohort == "TARGETED_41" }
        assertEquals(41, targetedDetails.size)
        // All 41 targeted sessions must be 100% INCLUDED
        for (detail in targetedDetails) {
            assertEquals("Targeted session ${detail.sessionId} must be INCLUDED", "INCLUDED", detail.inclusionStatus)
        }
    }

    @Test
    fun `test 05 - targeted cohort scenario distribution verification`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        val targetedDetails = auditResult.sessionDetails.filter { it.cohort == "TARGETED_41" }
        val scenarioCounts = targetedDetails.groupingBy { it.scenarioId }.eachCount()

        assertEquals(10, scenarioCounts["NORMAL_FOREGROUND_CAMERA"])
        assertEquals(8, scenarioCounts["BACKGROUND_CAMERA_CONTINUATION"])
        assertEquals(5, scenarioCounts["CAMERA_START_STOP"])
        assertEquals(5, scenarioCounts["AUTOMATED_BACKGROUND_TRIGGER"])
        assertEquals(5, scenarioCounts["PERMISSION_DENIED"])
        assertEquals(5, scenarioCounts["PERMISSION_GRANTED_NO_CAMERA"])
        assertEquals(3, scenarioCounts["AMBIGUOUS_CONTEXT"])
    }

    @Test
    fun `test 06 - camera hardware acquisition distribution`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        val targetedDetails = auditResult.sessionDetails.filter { it.cohort == "TARGETED_41" }
        val acqCount = targetedDetails.count { it.cameraHardwareAcquisition == "CAMERA_ACQUISITION" }
        val noAcqCount = targetedDetails.count { it.cameraHardwareAcquisition == "NO_CAMERA_CONTROL" }

        assertEquals(28, acqCount)
        assertEquals(13, noAcqCount)
        assertEquals(41, acqCount + noAcqCount)

        // Across all 92 accumulated sessions
        val allAcq = auditResult.sessionDetails.count { it.cameraHardwareAcquisition == "CAMERA_ACQUISITION" }
        val allNoAcq = auditResult.sessionDetails.count { it.cameraHardwareAcquisition == "NO_CAMERA_CONTROL" }
        assertEquals(57, allAcq)
        assertEquals(35, allNoAcq)
    }

    @Test
    fun `test 07 - lens facing distribution`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        val targetedDetails = auditResult.sessionDetails.filter { it.cohort == "TARGETED_41" }
        val backCount = targetedDetails.count { it.isBackCamera == "TRUE" }
        val frontCount = targetedDetails.count { it.isBackCamera == "FALSE" }
        val noneCount = targetedDetails.count { it.isBackCamera == "UNKNOWN" }

        assertEquals(23, backCount)
        assertEquals(5, frontCount)
        assertEquals(13, noneCount)
        assertEquals(41, backCount + frontCount + noneCount)
    }

    @Test
    fun `test 08 - screen-off background continuation forensic validation`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        val screenOffSids = listOf(
            "exp_20260926_000808_rep1_09f4c6",
            "exp_20260926_000829_rep2_057a8a",
            "exp_20260926_000855_rep3_9fe901"
        )

        for (sid in screenOffSids) {
            val detail = auditResult.sessionDetails.firstOrNull { it.sessionId == sid }
            assertNotNull("Screen-off session $sid must exist", detail)
            assertEquals("BACKGROUND_CAMERA_CONTINUATION", detail?.scenarioId)
            assertEquals("INCLUDED", detail?.inclusionStatus)
            assertEquals("CAMERA_ACQUISITION", detail?.cameraHardwareAcquisition)
            assertEquals("TRUE", detail?.isBackCamera)
            assertEquals("0", detail?.cameraHardwareId)
            // Screen transitions must contain OFF and ON_LOCKED
            val transitions = detail?.screenStateTransitions ?: ""
            assertTrue("Transitions must contain OFF: $transitions", transitions.contains("OFF"))
            assertTrue("Transitions must contain ON_LOCKED: $transitions", transitions.contains("ON_LOCKED"))
            assertTrue("Transitions must contain ON_UNLOCKED: $transitions", transitions.contains("ON_UNLOCKED"))
        }
    }

    @Test
    fun `test 09 - automated background trigger telemetry validation`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        val triggerDetails = auditResult.sessionDetails.filter {
            it.cohort == "TARGETED_41" && it.scenarioId == "AUTOMATED_BACKGROUND_TRIGGER"
        }
        assertEquals(5, triggerDetails.size)

        for (td in triggerDetails) {
            assertEquals("AUTOMATED_BACKGROUND_TRIGGER", td.scenarioId)
            assertEquals("INCLUDED", td.inclusionStatus)
            assertEquals("CAMERA_ACQUISITION", td.cameraHardwareAcquisition)
            assertEquals("AUTOMATED_TIMER", td.triggerMechanism)
            assertEquals(1, td.f06PackageConfidence) // LOW confidence
            assertEquals("USAGE_STATS_ACTIVITY_PAUSED", td.f07InferenceMethod)
        }
    }

    @Test
    fun `test 10 - negative control invariant validation`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        val negControls = auditResult.sessionDetails.filter {
            it.cohort == "TARGETED_41" && it.scenarioId in listOf("PERMISSION_DENIED", "PERMISSION_GRANTED_NO_CAMERA", "AMBIGUOUS_CONTEXT")
        }
        assertEquals(13, negControls.size)

        for (nc in negControls) {
            assertEquals("NO_CAMERA_CONTROL", nc.cameraHardwareAcquisition)
            assertEquals("UNKNOWN", nc.cameraHardwareId)
            assertEquals("UNKNOWN", nc.isBackCamera)
            assertEquals(0, nc.f06PackageConfidence)
            assertEquals("NONE", nc.f07InferenceMethod)
            assertEquals(0L, nc.f12DurationMs)
        }
    }

    @Test
    fun `test 11 - feature unfreezing audit F01 through F20`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        val faMap = auditResult.featureAudits.associateBy { it.featureId }
        assertEquals(20, faMap.size)

        // F10 and F11 unfrozen (Front camera now present)
        val f10 = faMap["F10"]!!
        assertTrue(f10.distinctValuesTargeted.contains("0"))
        assertTrue(f10.distinctValuesTargeted.contains("1"))
        assertTrue(f10.distinctValuesTargeted.contains("UNKNOWN"))
        assertFalse(f10.isConstantTargeted)

        val f11 = faMap["F11"]!!
        assertTrue(f11.distinctValuesTargeted.contains("TRUE"))
        assertTrue(f11.distinctValuesTargeted.contains("FALSE"))
        assertTrue(f11.distinctValuesTargeted.contains("UNKNOWN"))
        assertFalse(f11.isConstantTargeted)

        // F06 unfrozen (0, 1, 2 now present)
        val f06 = faMap["F06"]!!
        assertTrue(f06.distinctValuesTargeted.contains("0"))
        assertTrue(f06.distinctValuesTargeted.contains("1"))
        assertTrue(f06.distinctValuesTargeted.contains("2"))
        assertFalse(f06.isConstantTargeted)

        // F04 production eligibility
        val f04 = faMap["F04"]!!
        assertFalse("F04 cannot be production T0 eligible due to unprivileged sandbox", f04.productionT0Eligible)
    }

    @Test
    fun `test 12 - T0 production observability boundary`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        val prodFeatures = auditResult.featureAudits.filter { it.productionT0Eligible }
        assertEquals(10, prodFeatures.size) // F01, F02, F03, F05, F06, F07, F08, F09, F10, F11 (F04 is excluded)
        val nonProdFeatures = auditResult.featureAudits.filter { !it.productionT0Eligible }
        assertEquals(10, nonProdFeatures.size) // F04 (restricted) + F12..F20 (retrospective)
    }

    @Test
    fun `test 13 - cross-validation feasibility evaluation`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        val taskAssessments = auditResult.taskAssessments.associateBy { it.taskId }
        val task2 = taskAssessments["TASK_2_BINARY_HARDWARE_ACQUISITION"]!!
        assertEquals("READY", task2.readinessStatus)

        val task3 = taskAssessments["TASK_3_THREE_TIER_CONTEXT"]!!
        assertEquals("READY", task3.readinessStatus)

        val task4 = taskAssessments["TASK_4_MULTICLASS_SCENARIO"]!!
        assertEquals("CONDITIONALLY_READY", task4.readinessStatus)
    }

    @Test
    fun `test 14 - task readiness assessments across all 6 ML tasks`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        assertEquals(6, auditResult.taskAssessments.size)
        val taskMap = auditResult.taskAssessments.associateBy { it.taskId }

        assertEquals("READY", taskMap["TASK_1_DETERMINISTIC_BASELINE"]?.readinessStatus)
        assertEquals("READY", taskMap["TASK_2_BINARY_HARDWARE_ACQUISITION"]?.readinessStatus)
        assertEquals("READY", taskMap["TASK_3_THREE_TIER_CONTEXT"]?.readinessStatus)
        assertEquals("CONDITIONALLY_READY", taskMap["TASK_4_MULTICLASS_SCENARIO"]?.readinessStatus)
        assertEquals("READY", taskMap["TASK_5_ANOMALY_DETECTION"]?.readinessStatus)
        assertEquals("READY", taskMap["TASK_6_HYBRID_RULE_ML_ENSEMBLE"]?.readinessStatus)
    }

    @Test
    fun `test 15 - full audit artifact generation and export to data derived phase4 audit`() {
        val rawDir = getTargetRawDir() ?: return
        val auditResult = Phase463DatasetAuditor.performFullAudit(rawDir)

        val outDir = File(rawDir.parentFile, "derived/phase4/audit")
        Phase463DatasetAuditor.exportArtifacts(auditResult, outDir)

        val datasetAuditCsv = File(outDir, "dataset_audit.csv")
        val scenarioDistCsv = File(outDir, "scenario_distribution.csv")
        val featureAuditCsv = File(outDir, "feature_audit.csv")
        val manifestJson = File(outDir, "audit_manifest.json")

        assertTrue("dataset_audit.csv must exist", datasetAuditCsv.exists())
        assertTrue("scenario_distribution.csv must exist", scenarioDistCsv.exists())
        assertTrue("feature_audit.csv must exist", featureAuditCsv.exists())
        assertTrue("audit_manifest.json must exist", manifestJson.exists())

        // Line counts
        val datasetLines = datasetAuditCsv.readLines()
        assertEquals(93, datasetLines.size) // 1 header + 92 sessions

        val featureLines = featureAuditCsv.readLines()
        assertEquals(21, featureLines.size) // 1 header + 20 features

        val manifestText = manifestJson.readText()
        assertTrue(manifestText.contains("\"audit_version\": \"4.6.3\""))
        assertTrue(manifestText.contains("\"overall_readiness_status\": \"READY_FOR_MULTI_MODEL_EXPERIMENTATION\""))
        assertTrue(manifestText.contains("\"total_targeted_sessions\": 41"))
        assertTrue(manifestText.contains("\"total_raw_files\": 15"))
        assertTrue(manifestText.contains("\"covert_malware_samples_count\": 0"))
    }
}
