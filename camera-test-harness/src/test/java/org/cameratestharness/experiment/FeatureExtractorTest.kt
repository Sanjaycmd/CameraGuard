package org.cameratestharness.experiment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeatureExtractorTest {

    @Test
    fun `test 1 - formal feature catalog defines exactly 20 features with stable ordering`() {
        val all = FeatureCatalog.ALL_FEATURES
        assertEquals(20, all.size)

        val t0 = FeatureCatalog.getT0Features()
        val t2 = FeatureCatalog.getT2Features()
        assertEquals(11, t0.size)
        assertEquals(9, t2.size)

        // Verify ID sequence F01..F20
        for (i in 1..20) {
            val expectedId = String.format("F%02d", i)
            assertEquals(expectedId, all[i - 1].featureId)
        }

        // T0 features are strictly F01..F11
        for (i in 1..11) {
            val expectedId = String.format("F%02d", i)
            assertEquals(expectedId, t0[i - 1].featureId)
            assertEquals(FeatureDefinition.TemporalWindow.T0_ACTIVATION, t0[i - 1].temporalWindow)
        }

        // T2 features are strictly F12..F20
        for (i in 12..20) {
            val expectedId = String.format("F%02d", i)
            assertEquals(expectedId, t2[i - 12].featureId)
            assertEquals(FeatureDefinition.TemporalWindow.T2_RETROSPECTIVE, t2[i - 12].temporalWindow)
            assertTrue(t2[i - 12].isResearchOnly)
        }
    }

    @Test
    fun `test 2 - deterministic schema CSV generation is stable`() {
        val csv1 = FeatureCatalog.toSchemaCsv()
        val csv2 = FeatureCatalog.toSchemaCsv()
        assertEquals("Schema CSV must be 100% deterministic across calls", csv1, csv2)

        val lines = csv1.split("\n")
        assertEquals("Header + 20 feature definitions = 21 lines", 21, lines.size)
        assertEquals(FeatureDefinition.SCHEMA_CSV_HEADER, lines[0])
        assertTrue(lines[1].startsWith("F01,screen_state_code,"))
        assertTrue(lines[20].startsWith("F20,returned_to_foreground,"))
    }

    @Test
    fun `test 3 - clean foreground session extracts valid T0 vector without leakage`() {
        val records = listOf(
            createRecord(sampleId = "exp_clean_fg", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_START", screen = "ON_UNLOCKED", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_clean_fg", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", camId = "0", screen = "ON_UNLOCKED", fgs = true, ts = "2026-09-25T10:00:00.120Z"),
            createRecord(sampleId = "exp_clean_fg", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", userAction = "USER_PRESSED_STOP", ts = "2026-09-25T10:00:05.000Z"),
            createRecord(sampleId = "exp_clean_fg", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", fgs = false, ts = "2026-09-25T10:00:05.100Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals(1, sessions.size)

        val row = FeatureExtractor.extractFromSession(sessions[0])
        assertEquals("exp_clean_fg", row.sessionId)
        assertEquals("NORMAL_FOREGROUND_CAMERA", row.auditScenarioId)
        assertEquals("USER_INITIATED_FOREGROUND", row.groundTruthContext)
        assertEquals("INCLUDED", row.inclusionStatus)
        assertNull(row.exclusionReason)

        // T0 Production Feature checks
        val t0 = row.t0Features
        assertEquals("ON_UNLOCKED", t0.f01ScreenStateCode)
        assertEquals("TRUE", t0.f02IsScreenInteractive)
        assertEquals("FALSE", t0.f03IsDeviceLocked)
        assertEquals("GRANTED", t0.f04HasCameraPermission)
        assertFalse(t0.f05IsKnownCameraApp)
        assertEquals(2, t0.f06PackageInferenceConfidence) // Medium for foreground
        assertEquals("USAGE_STATS_ACTIVITY_RESUMED", t0.f07InferenceMethodCode)
        assertEquals(120.0, t0.f08DeltaResumedToTriggerMs, 0.001)
        assertEquals(1, t0.f09RecentActivityCount30s)
        assertEquals("0", t0.f10CameraHardwareId)
        assertEquals("TRUE", t0.f11IsBackCamera)

        // Automated leakage assertion
        FeatureExtractor.assertNoLeakage(row)

        // Retrospective T2 fields must be separated
        assertEquals(5100L, row.f12SessionDurationMs)
        assertEquals("TRUE", row.f13ExplicitUserStartAction)
        assertEquals("TRUE", row.f14ExplicitUserStopAction)
    }

    @Test
    fun `test 4 - strict production observability enforces UNVERIFIED for permission`() {
        val records = listOf(
            createRecord(sampleId = "exp_strict_obs", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", perm = "GRANTED", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:00:00.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val row = FeatureExtractor.extractFromSession(sessions[0], strictProductionObservability = true)

        assertEquals("In strict production observability mode, F04 must be UNVERIFIED", "UNVERIFIED", row.t0Features.f04HasCameraPermission)
    }

    @Test
    fun `test 5 - unknown screen state preserves UNKNOWN without false coercion`() {
        val records = listOf(
            createRecord(sampleId = "exp_unk_screen", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", screen = "UNKNOWN", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:00:00.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val row = FeatureExtractor.extractFromSession(sessions[0])

        assertEquals("UNKNOWN", row.t0Features.f01ScreenStateCode)
        assertEquals("UNKNOWN", row.t0Features.f02IsScreenInteractive)
        assertEquals("UNKNOWN", row.t0Features.f03IsDeviceLocked)

        val numVec = row.t0Features.toNumericalVector()
        assertEquals(-1.0, numVec[0], 0.001) // F01 encoded as -1.0 sentinel
        assertEquals(-1.0, numVec[1], 0.001) // F02 encoded as -1.0 sentinel
        assertEquals(-1.0, numVec[2], 0.001) // F03 encoded as -1.0 sentinel
    }

    @Test
    fun `test 6 - background continuation session extracts multi-transition features`() {
        val records = listOf(
            createRecord(sampleId = "exp_bg_cont", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_START", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_bg_cont", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", fgs = true, vis = "FOREGROUND", ts = "2026-09-25T10:00:00.100Z"),
            createRecord(sampleId = "exp_bg_cont", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "USER_INITIATED_BACKGROUND_CONTINUATION", userAction = "APP_ENTERED_BACKGROUND", vis = "BACKGROUND", actState = "STOPPED", fgs = true, ts = "2026-09-25T10:00:02.000Z"),
            createRecord(sampleId = "exp_bg_cont", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "USER_INITIATED_BACKGROUND_CONTINUATION", userAction = "APP_RETURNED_FOREGROUND", vis = "FOREGROUND", actState = "RESUMED", fgs = true, ts = "2026-09-25T10:00:05.000Z"),
            createRecord(sampleId = "exp_bg_cont", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "CAMERA_SESSION_CLOSED", userAction = "USER_PRESSED_STOP", ts = "2026-09-25T10:00:06.000Z"),
            createRecord(sampleId = "exp_bg_cont", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", fgs = false, ts = "2026-09-25T10:00:06.100Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val row = FeatureExtractor.extractFromSession(sessions[0])

        assertEquals("BACKGROUND_CAMERA_CONTINUATION", row.auditScenarioId)
        assertEquals("USER_INITIATED_BACKGROUND_CONTINUATION", row.groundTruthContext)
        assertEquals(2, row.t0Features.f09RecentActivityCount30s) // Multitasking transitions

        // Retrospective verification
        assertTrue(row.f19BackgroundedDuringSession)
        assertTrue(row.f20ReturnedToForeground)
        assertTrue(row.f16HarnessFgServiceActive)

        FeatureExtractor.assertNoLeakage(row)
    }

    @Test
    fun `test 7 - automated background trigger extracts without user START action and no recent resume`() {
        val records = listOf(
            createRecord(sampleId = "exp_auto", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "AUTOMATED_BACKGROUND_TRIGGER", userAction = "ARM_AUTOMATED_TRIGGER", fgs = true, ts = "2026-09-25T10:00:00.000Z", notes = "trigger=countdown_timer"),
            createRecord(sampleId = "exp_auto", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "AUTOMATED_BACKGROUND_TRIGGER", userAction = "AUTOMATED_TRIGGER_FIRED", camEvent = "CAMERA_OPENED", fgs = true, vis = "BACKGROUND", actState = "STOPPED", recent = false, ts = "2026-09-25T10:00:05.000Z"),
            createRecord(sampleId = "exp_auto", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "CAMERA_SESSION_CLOSED", userAction = "USER_PRESSED_STOP", ts = "2026-09-25T10:00:10.000Z"),
            createRecord(sampleId = "exp_auto", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", fgs = false, ts = "2026-09-25T10:00:10.100Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val row = FeatureExtractor.extractFromSession(sessions[0])

        assertEquals("AUTOMATED_BACKGROUND_TRIGGER", row.groundTruthContext)
        assertEquals(-1.0, row.t0Features.f08DeltaResumedToTriggerMs, 0.001) // No recent resume
        assertEquals(0, row.t0Features.f09RecentActivityCount30s)
        assertEquals(1, row.t0Features.f06PackageInferenceConfidence) // Low for background

        // F13 start action must be FALSE (no user start button clicked)
        assertEquals("FALSE", row.f13ExplicitUserStartAction)

        FeatureExtractor.assertNoLeakage(row)
    }

    @Test
    fun `test 8 - excluded contradictory session retains EXCLUDED status with reason`() {
        // PERMISSION_DENIED scenario with camera erroneously opened
        val records = listOf(
            createRecord(sampleId = "exp_bad_contradiction", scenario = "PERMISSION_DENIED", gt = "PERMISSION_DENIED", perm = "DENIED", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:00:00.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val row = FeatureExtractor.extractFromSession(sessions[0])

        assertEquals("EXCLUDED", row.inclusionStatus)
        assertNotNull(row.exclusionReason)
        val reason = row.exclusionReason ?: ""
        assertTrue(reason.contains("ground-truth", ignoreCase = true) || reason.contains("invariants", ignoreCase = true))
    }

    @Test
    fun `test 9 - numerical feature vector matches documented encodings`() {
        val t0 = T0ProductionFeatureVector(
            f01ScreenStateCode = "ON_UNLOCKED",
            f02IsScreenInteractive = "TRUE",
            f03IsDeviceLocked = "FALSE",
            f04HasCameraPermission = "GRANTED",
            f05IsKnownCameraApp = true,
            f06PackageInferenceConfidence = 3,
            f07InferenceMethodCode = "USAGE_STATS_ACTIVITY_RESUMED",
            f08DeltaResumedToTriggerMs = 150.0,
            f09RecentActivityCount30s = 5,
            f10CameraHardwareId = "0",
            f11IsBackCamera = "TRUE"
        )

        val vec = t0.toNumericalVector()
        assertEquals(11, vec.size)
        assertEquals(1.0, vec[0], 0.001) // ON_UNLOCKED
        assertEquals(1.0, vec[1], 0.001) // TRUE
        assertEquals(0.0, vec[2], 0.001) // FALSE
        assertEquals(1.0, vec[3], 0.001) // GRANTED
        assertEquals(1.0, vec[4], 0.001) // isKnownCameraApp = true
        assertEquals(3.0, vec[5], 0.001) // HIGH confidence = 3
        assertEquals(1.0, vec[6], 0.001) // USAGE_STATS_ACTIVITY_RESUMED = 1.0
        assertEquals(150.0, vec[7], 0.001) // delta ms
        assertEquals(5.0, vec[8], 0.001) // activity count
        assertEquals(0.0, vec[9], 0.001) // Camera 0 (back) = 0.0
        assertEquals(1.0, vec[10], 0.001) // TRUE back camera
    }

    @Test
    fun `test 10 - determinism across repeated extractions`() {
        val records = listOf(
            createRecord(sampleId = "exp_det_1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_START", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_det_1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", fgs = true, ts = "2026-09-25T10:00:00.100Z"),
            createRecord(sampleId = "exp_det_1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)

        val rows1 = FeatureExtractor.extractAll(sessions)
        val rows2 = FeatureExtractor.extractAll(sessions)

        assertEquals(rows1.size, rows2.size)
        assertEquals("Full CSV rows must be identical across runs", rows1[0].toFullCsvRow(), rows2[0].toFullCsvRow())
        assertEquals("Production CSV rows must be identical across runs", rows1[0].toProductionCsvRow(), rows2[0].toProductionCsvRow())
    }

    @Test
    fun `test 11 - CSV escaping and structure consistency`() {
        val row = FeatureDatasetRow(
            sessionId = "exp_csv_test",
            auditScenarioId = "NORMAL_FOREGROUND_CAMERA",
            repetition = 1,
            timestampIso = "2026-09-25T10:00:00.000Z",
            datasetVersion = "1.0.0",
            inclusionStatus = "INCLUDED",
            exclusionReason = null,
            groundTruthContext = "USER_INITIATED_FOREGROUND",
            t0Features = T0ProductionFeatureVector(
                f01ScreenStateCode = "ON_UNLOCKED",
                f02IsScreenInteractive = "TRUE",
                f03IsDeviceLocked = "FALSE",
                f04HasCameraPermission = "GRANTED",
                f05IsKnownCameraApp = false,
                f06PackageInferenceConfidence = 2,
                f07InferenceMethodCode = "USAGE_STATS_ACTIVITY_RESUMED",
                f08DeltaResumedToTriggerMs = 120.0,
                f09RecentActivityCount30s = 1,
                f10CameraHardwareId = "0",
                f11IsBackCamera = "TRUE"
            ),
            f12SessionDurationMs = 5000L,
            f13ExplicitUserStartAction = "TRUE",
            f14ExplicitUserStopAction = "TRUE",
            f15InternalHarnessState = "IDLE",
            f16HarnessFgServiceActive = true,
            f17TimeToSessionCloseMs = 5000L,
            f18AppVisibilityAtTrigger = "FOREGROUND",
            f19BackgroundedDuringSession = false,
            f20ReturnedToForeground = false
        )

        val fullCsv = row.toFullCsvRow()
        val prodCsv = row.toProductionCsvRow()

        val fullHeaderCols = FeatureDatasetRow.FULL_CSV_HEADER.split(",")
        val fullDataCols = fullCsv.split(",")
        assertEquals("Full header and data columns count must match", fullHeaderCols.size, fullDataCols.size)

        val prodHeaderCols = FeatureDatasetRow.PRODUCTION_CSV_HEADER.split(",")
        val prodDataCols = prodCsv.split(",")
        assertEquals("Production header and data columns count must match", prodHeaderCols.size, prodDataCols.size)
        assertEquals(8 + 11, prodHeaderCols.size) // 8 audit + 11 T0 features
    }

    @Test
    fun `test 12 - summary statistics and manifest compute correctly`() {
        val records = listOf(
            createRecord(sampleId = "exp_stat_1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_START", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_stat_1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", fgs = true, ts = "2026-09-25T10:00:00.100Z"),
            createRecord(sampleId = "exp_stat_1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z"),
            // Second session: permission denied
            createRecord(sampleId = "exp_stat_2", scenario = "PERMISSION_DENIED", gt = "PERMISSION_DENIED", perm = "DENIED", ts = "2026-09-25T10:10:00.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val rows = FeatureExtractor.extractAll(sessions)

        val stats = FeatureExtractor.computeStatistics(rows)
        assertEquals(2, stats.totalSessionsExtracted)
        assertEquals(2, stats.includedSessionsCount)
        assertEquals(0, stats.excludedSessionsCount)
        assertEquals(11, stats.t0FeatureCount)
        assertEquals(9, stats.t2FeatureCount)
        assertTrue(stats.classDistribution.containsKey("USER_INITIATED_FOREGROUND"))
        assertTrue(stats.classDistribution.containsKey("PERMISSION_DENIED"))
    }

    @Test
    fun `test 13 - generate and export derived phase 4 dataset artifacts from physical raw data`() {
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
        assertEquals("Must reconstruct all 16 physical sessions", 16, sessions.size)

        val derivedRows = FeatureExtractor.extractAll(sessions)
        assertEquals(16, derivedRows.size)

        val outDir = File(targetRawDir.parentFile, "derived/phase4")
        FeatureExtractor.exportDerivedDataset(derivedRows, allRecords.size, outDir)

        assertTrue(File(outDir, "feature_dataset.csv").exists())
        assertTrue(File(outDir, "t0_production_features.csv").exists())
        assertTrue(File(outDir, "feature_schema.csv").exists())
        assertTrue(File(outDir, "feature_extraction_manifest.json").exists())

        // Verify no leakage across all extracted physical rows
        for (r in derivedRows) {
            FeatureExtractor.assertNoLeakage(r)
        }
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
