package org.cameratestharness.experiment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BaselineEvaluatorTest {

    @Test
    fun `test 1 - legitimate foreground camera session evaluates with Phase 2 rules`() {
        val records = listOf(
            createRecord(sampleId = "exp_legit_fg", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_START", screen = "ON_UNLOCKED", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_legit_fg", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", camId = "0", screen = "ON_UNLOCKED", fgs = true, ts = "2026-09-25T10:00:00.120Z"),
            createRecord(sampleId = "exp_legit_fg", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", userAction = "USER_PRESSED_STOP", ts = "2026-09-25T10:00:05.000Z"),
            createRecord(sampleId = "exp_legit_fg", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", fgs = false, ts = "2026-09-25T10:00:05.100Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals(1, sessions.size)

        // Strict production observability: permission is unverified from sandbox
        val recordStrict = BaselineEvaluator.evaluateSession(sessions[0], strictProductionObservability = true)
        assertEquals("CAMERA_ACTIVATION", recordStrict.evaluationSubset)
        assertTrue(recordStrict.actualCameraActivity)
        assertEquals("UNKNOWN", recordStrict.baselineClassification) // Rule 5: permission unverified from sandbox
        assertFalse(recordStrict.isDecisive)

        // Oracle permission mode: permission is verified as granted
        val recordOracle = BaselineEvaluator.evaluateSession(sessions[0], strictProductionObservability = false)
        assertEquals("EXPECTED", recordOracle.baselineClassification) // Rule 4: screen ON_UNLOCKED + granted + medium confidence
        assertTrue(recordOracle.isDecisive)
        assertEquals("RULE_4_EXPECTED_USER_INTERACTION", recordOracle.ruleTriggered)
    }

    @Test
    fun `test 2 - legitimate background continuation evaluates with Phase 2 rules`() {
        val records = listOf(
            createRecord(sampleId = "exp_bg_cont", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_START", screen = "ON_UNLOCKED", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_bg_cont", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", camId = "0", fgs = true, vis = "FOREGROUND", ts = "2026-09-25T10:00:00.100Z"),
            createRecord(sampleId = "exp_bg_cont", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "USER_INITIATED_BACKGROUND_CONTINUATION", userAction = "APP_ENTERED_BACKGROUND", vis = "BACKGROUND", actState = "STOPPED", fgs = true, ts = "2026-09-25T10:00:02.000Z"),
            createRecord(sampleId = "exp_bg_cont", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "CAMERA_SESSION_CLOSED", userAction = "USER_PRESSED_STOP", ts = "2026-09-25T10:00:06.000Z"),
            createRecord(sampleId = "exp_bg_cont", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", fgs = false, ts = "2026-09-25T10:00:06.100Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val record = BaselineEvaluator.evaluateSession(sessions[0], strictProductionObservability = false)

        assertEquals("CAMERA_ACTIVATION", record.evaluationSubset)
        // In background continuation, confidence is LOW (app backgrounded / paused), so Rule 4 does not fire; Rule 5 fires UNKNOWN
        assertEquals("UNKNOWN", record.baselineClassification)
        assertEquals("RULE_5_INCONCLUSIVE_UNKNOWN", record.ruleTriggered)
    }

    @Test
    fun `test 3 - permission denied negative control produces NO_CAMERA_EVENT`() {
        val records = listOf(
            createRecord(sampleId = "exp_perm_denied", scenario = "PERMISSION_DENIED", gt = "PERMISSION_DENIED", userAction = "USER_EVALUATED_PERMISSION_DENIED", perm = "DENIED", camEvent = "NONE", ts = "2026-09-25T10:00:00.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val record = BaselineEvaluator.evaluateSession(sessions[0])

        assertFalse(record.actualCameraActivity)
        assertEquals("NO_CAMERA_EVENT", record.baselineClassification)
        assertEquals("NO_CAMERA_CONTROL", record.evaluationSubset)
        assertTrue(record.isDecisive)
    }

    @Test
    fun `test 4 - permission granted no camera control produces NO_CAMERA_EVENT`() {
        val records = listOf(
            createRecord(sampleId = "exp_perm_granted_no_cam", scenario = "PERMISSION_GRANTED_NO_CAMERA", gt = "NO_CAMERA_ACTIVITY", userAction = "USER_EVALUATED_NO_CAMERA", perm = "GRANTED", camEvent = "NONE", ts = "2026-09-25T10:00:00.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val record = BaselineEvaluator.evaluateSession(sessions[0])

        assertFalse(record.actualCameraActivity)
        assertEquals("NO_CAMERA_EVENT", record.baselineClassification)
        assertEquals("NO_CAMERA_CONTROL", record.evaluationSubset)
        assertTrue(record.isDecisive)
    }

    @Test
    fun `test 5 - ambiguous context observation produces NO_CAMERA_EVENT`() {
        val records = listOf(
            createRecord(sampleId = "exp_amb_obs", scenario = "AMBIGUOUS_CONTEXT", gt = "AMBIGUOUS_CONTEXT", userAction = "USER_EVALUATED_OBSERVATION", camEvent = "NONE", ts = "2026-09-25T10:00:00.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val record = BaselineEvaluator.evaluateSession(sessions[0])

        assertFalse(record.actualCameraActivity)
        assertEquals("NO_CAMERA_EVENT", record.baselineClassification)
        assertEquals("NO_CAMERA_CONTROL", record.evaluationSubset)
    }

    @Test
    fun `test 6 - automated background trigger evaluates as UNEXPECTED when screen is OFF or locked`() {
        // Condition A: Screen is OFF
        val offRecords = listOf(
            createRecord(sampleId = "exp_auto_off", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "AUTOMATED_BACKGROUND_TRIGGER", userAction = "AUTOMATED_TRIGGER_FIRED", camEvent = "CAMERA_OPENED", screen = "OFF", fgs = true, ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_auto_off", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z")
        )

        val offSessions = SessionReconstructor.reconstructSessions(offRecords)
        val offRecord = BaselineEvaluator.evaluateSession(offSessions[0])

        assertEquals("CAMERA_ACTIVATION", offRecord.evaluationSubset)
        assertEquals("UNEXPECTED", offRecord.baselineClassification)
        assertEquals("RULE_1_SCREEN_OFF", offRecord.ruleTriggered)
        assertTrue(offRecord.isDecisive)

        // Condition B: Screen is ON_LOCKED
        val lockedRecords = listOf(
            createRecord(sampleId = "exp_auto_locked", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "AUTOMATED_BACKGROUND_TRIGGER", userAction = "AUTOMATED_TRIGGER_FIRED", camEvent = "CAMERA_OPENED", screen = "ON_LOCKED", fgs = true, ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_auto_locked", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z")
        )

        val lockedSessions = SessionReconstructor.reconstructSessions(lockedRecords)
        val lockedRecord = BaselineEvaluator.evaluateSession(lockedSessions[0])

        assertEquals("UNEXPECTED", lockedRecord.baselineClassification)
        assertEquals("RULE_2_DEVICE_LOCKED", lockedRecord.ruleTriggered)
        assertTrue(lockedRecord.isDecisive)
    }

    @Test
    fun `test 7 - UNKNOWN screen telemetry falls back to default without crash`() {
        val records = listOf(
            createRecord(sampleId = "exp_unk_telemetry", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", screen = "UNKNOWN", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_unk_telemetry", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val record = BaselineEvaluator.evaluateSession(sessions[0])

        assertNotNull(record.baselineClassification)
        assertNotNull(record.baselineExplanation)
    }

    @Test
    fun `test 8 - UNVERIFIED permission produces UNKNOWN explanation in production mode`() {
        val records = listOf(
            createRecord(sampleId = "exp_unverified_perm", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", perm = "UNKNOWN", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_unverified_perm", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val record = BaselineEvaluator.evaluateSession(sessions[0], strictProductionObservability = true)

        assertEquals("UNKNOWN", record.baselineClassification)
        assertTrue(record.baselineExplanation.contains("Camera permission could not be verified from sandbox"))
    }

    @Test
    fun `test 9 - duplicate legacy sessions are marked for review and handled`() {
        val records = listOf(
            createRecord(sampleId = "exp_dup_legacy", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_START", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_dup_legacy", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:00:00.100Z"),
            createRecord(sampleId = "exp_dup_legacy", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z"),
            createRecord(sampleId = "exp_dup_legacy", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.100Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals(1, sessions.size)
        assertTrue(sessions[0].hasDuplicates)
        assertTrue(sessions[0].reviewRequired)
    }

    @Test
    fun `test 10 - contradictory ground truth session retains EXCLUDED status`() {
        val records = listOf(
            createRecord(sampleId = "exp_contradictory", scenario = "PERMISSION_DENIED", gt = "PERMISSION_DENIED", perm = "DENIED", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:00:00.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val record = BaselineEvaluator.evaluateSession(sessions[0])

        assertEquals("EXCLUDED", record.inclusionStatus)
        assertNotNull(record.exclusionReason)
    }

    @Test
    fun `test 11 - session-level aggregation evaluates one unit per session ID`() {
        val records = listOf(
            createRecord(sampleId = "sess_1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "sess_1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAPTURE_SESSION_STARTED", ts = "2026-09-25T10:00:00.100Z"),
            createRecord(sampleId = "sess_1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z"),
            // Session 2
            createRecord(sampleId = "sess_2", scenario = "PERMISSION_DENIED", gt = "PERMISSION_DENIED", camEvent = "NONE", ts = "2026-09-25T10:10:00.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals(2, sessions.size)

        val evaluated = BaselineEvaluator.evaluateAll(sessions)
        assertEquals(2, evaluated.size)
        assertEquals("sess_1", evaluated[0].sessionId)
        assertEquals("sess_2", evaluated[1].sessionId)
    }

    @Test
    fun `test 12 - deterministic repeated evaluation produces identical results`() {
        val records = listOf(
            createRecord(sampleId = "exp_det_eval", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_det_eval", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)

        val eval1 = BaselineEvaluator.evaluateAll(sessions)
        val eval2 = BaselineEvaluator.evaluateAll(sessions)

        assertEquals(eval1.size, eval2.size)
        assertEquals(eval1[0].toCsvRow(), eval2[0].toCsvRow())
    }

    @Test
    fun `test 13 - no ground-truth leakage into baseline prediction logic`() {
        val records = listOf(
            createRecord(sampleId = "exp_leak_check", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "AUTOMATED_BACKGROUND_TRIGGER", userAction = "AUTOMATED_TRIGGER_FIRED", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_leak_check", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val record = BaselineEvaluator.evaluateSession(sessions[0])

        // Baseline classification should be UNKNOWN (Rule 5) when screen is ON_UNLOCKED and confidence is LOW.
        // It must NOT say "AUTOMATED_BACKGROUND_TRIGGER" or "MALICIOUS" or "UNAUTHORIZED"
        assertEquals("UNKNOWN", record.baselineClassification)
        assertFalse(record.baselineExplanation.contains("AUTOMATED_BACKGROUND_TRIGGER"))
        assertFalse(record.baselineExplanation.contains("MALICIOUS"))
        assertFalse(record.baselineExplanation.contains("UNAUTHORIZED"))
    }

    @Test
    fun `test 14 - metrics computation computes rates with denominators`() {
        val dummyRecords = listOf(
            BaselinePredictionRecord("s1", "NORMAL_FOREGROUND_CAMERA", 1, "USER_INITIATED_FOREGROUND", true, "OPENED_AND_CLOSED", "EXPECTED", "ok", "R4", "pkg", "MEDIUM", "RESUMED", "ON_UNLOCKED", "GRANTED", "CAMERA_ACTIVATION", true, "INCLUDED", null),
            BaselinePredictionRecord("s2", "NORMAL_FOREGROUND_CAMERA", 1, "USER_INITIATED_FOREGROUND", true, "OPENED_AND_CLOSED", "UNKNOWN", "unk", "R5", "pkg", "LOW", "RESUMED", "ON_UNLOCKED", "UNVERIFIED", "CAMERA_ACTIVATION", false, "INCLUDED", null),
            BaselinePredictionRecord("s3", "AUTOMATED_BACKGROUND_TRIGGER", 1, "AUTOMATED_BACKGROUND_TRIGGER", true, "OPENED_AND_CLOSED", "UNEXPECTED", "bad", "R1", "pkg", "LOW", "PAUSED", "SCREEN_OFF", "UNVERIFIED", "CAMERA_ACTIVATION", true, "INCLUDED", null),
            BaselinePredictionRecord("s4", "PERMISSION_DENIED", 1, "PERMISSION_DENIED", false, "NO_CAMERA", "NO_CAMERA_EVENT", "none", "NONE", "NONE", "NONE", "NONE", "UNKNOWN", "DENIED", "NO_CAMERA_CONTROL", true, "INCLUDED", null)
        )

        val metrics = BaselineEvaluator.computeMetrics(dummyRecords)
        assertEquals(4, metrics.totalSessions)
        assertEquals(4, metrics.includedSessions)
        assertEquals(3, metrics.cameraActivationSessions)
        assertEquals(1, metrics.noCameraControlSessions)
        assertEquals(2, metrics.activationDecisiveCount)
        assertEquals(1, metrics.activationUnknownCount)
        assertEquals(2.0 / 3.0, metrics.activationCoverageRate, 0.001)
        assertEquals(1.0 / 3.0, metrics.activationIndeterminateRate, 0.001)
        assertEquals(1, metrics.alertCount) // 1 UNEXPECTED
        assertEquals(3, metrics.nonAlertCount)
        assertEquals(0.25, metrics.alertRate, 0.001)
    }

    @Test
    fun `test 15 - confusion matrix generates correct cell counts`() {
        val dummyRecords = listOf(
            BaselinePredictionRecord("s1", "NORMAL_FOREGROUND_CAMERA", 1, "USER_INITIATED_FOREGROUND", true, "OPENED_AND_CLOSED", "EXPECTED", "ok", "R4", "pkg", "MEDIUM", "RESUMED", "ON_UNLOCKED", "GRANTED", "CAMERA_ACTIVATION", true, "INCLUDED", null),
            BaselinePredictionRecord("s2", "PERMISSION_DENIED", 1, "PERMISSION_DENIED", false, "NO_CAMERA", "NO_CAMERA_EVENT", "none", "NONE", "NONE", "NONE", "NONE", "UNKNOWN", "DENIED", "NO_CAMERA_CONTROL", true, "INCLUDED", null)
        )

        val matrix = BaselineEvaluator.generateConfusionMatrix(dummyRecords)
        assertEquals(1, matrix["USER_INITIATED_FOREGROUND"]?.get("EXPECTED"))
        assertEquals(0, matrix["USER_INITIATED_FOREGROUND"]?.get("UNEXPECTED"))
        assertEquals(1, matrix["PERMISSION_DENIED"]?.get("NO_CAMERA_EVENT"))
        assertEquals(0, matrix["PERMISSION_DENIED"]?.get("EXPECTED"))
    }

    @Test
    fun `test 16 - zero-denominator handling produces zero rate without NaN`() {
        val emptyRecords = emptyList<BaselinePredictionRecord>()
        val metrics = BaselineEvaluator.computeMetrics(emptyRecords)

        assertEquals(0.0, metrics.activationCoverageRate, 0.001)
        assertEquals(0.0, metrics.activationIndeterminateRate, 0.001)
        assertEquals(0.0, metrics.alertRate, 0.001)
    }

    @Test
    fun `test 17 - physical raw dataset baseline evaluation and artifact export`() {
        val rawDir = File("../data/raw")
        val fallbackRawDir = File("data/raw")
        val targetRawDir = if (rawDir.exists()) rawDir else fallbackRawDir
        if (!targetRawDir.exists()) return

        val baselineFiles = setOf(
            "cameraguard_experiment_20260925_205319.csv",
            "cameraguard_experiment_20260925_205652.csv",
            "cameraguard_experiment_20260925_213654.csv",
            "persisted_experiment_history.csv"
        )
        val csvFiles = targetRawDir.listFiles { _, name -> name in baselineFiles }?.sortedBy { it.name } ?: return
        val allRecords = mutableListOf<ExperimentRecord>()
        for (f in csvFiles) {
            val records = ExperimentDataExporter.parseCsv(f.readText())
            allRecords.addAll(records)
        }

        val sessions = SessionReconstructor.reconstructSessions(allRecords)
        assertEquals(16, sessions.size)

        val evaluated = BaselineEvaluator.evaluateAll(sessions, strictProductionObservability = true)
        assertEquals(16, evaluated.size)

        val outDir = File(targetRawDir.parentFile, "derived/phase4/baseline")
        BaselineEvaluator.exportEvaluationResults(evaluated, outDir)

        assertTrue(File(outDir, "baseline_predictions.csv").exists())
        assertTrue(File(outDir, "baseline_metrics.csv").exists())
        assertTrue(File(outDir, "baseline_confusion_matrix.csv").exists())
        assertTrue(File(outDir, "baseline_manifest.json").exists())

        // Verify metrics content
        val metrics = BaselineEvaluator.computeMetrics(evaluated)
        assertEquals(16, metrics.totalSessions)
        assertEquals(13, metrics.includedSessions)
        assertEquals(3, metrics.excludedSessions)
        assertEquals(5, metrics.cameraActivationSessions)
        assertEquals(8, metrics.noCameraControlSessions)
    }

    private fun createRecord(
        sampleId: String = "exp_default",
        scenario: String = "NORMAL_FOREGROUND_CAMERA",
        gt: String = "USER_INITIATED_FOREGROUND",
        userAction: String = "NONE",
        camEvent: String = "NONE",
        camId: String = "0",
        pkg: String = "org.cameratestharness",
        perm: String = "GRANTED",
        actState: String = "RESUMED",
        vis: String = "FOREGROUND",
        fgs: Boolean = false,
        fgsType: String = if (fgs) "camera" else "none",
        screen: String = "ON",
        sessState: String = "RUNNING",
        durMs: String = "1000",
        recent: Boolean = true,
        lifecycle: String = "NONE",
        avail: String = "UNAVAILABLE",
        ts: String = "2026-09-25T10:00:00.000Z",
        notes: String = ""
    ): ExperimentRecord {
        return ExperimentRecord(
            sampleId = sampleId,
            timestamp = ts,
            scenarioId = scenario,
            groundTruthContext = gt,
            userAction = userAction,
            cameraEvent = camEvent,
            cameraId = camId,
            packageName = pkg,
            cameraPermission = perm,
            activityState = actState,
            appVisibility = vis,
            foregroundServiceActive = fgs,
            foregroundServiceType = fgsType,
            screenState = screen,
            sessionState = sessState,
            sessionDurationMs = durMs,
            recentUserInteraction = recent,
            lifecycleEvent = lifecycle,
            cameraAvailability = avail,
            notes = notes
        )
    }
}
