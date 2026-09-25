package org.cameratestharness.experiment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReconstructorTest {

    @Test
    fun `test 1 - normal foreground session reconstructs cleanly`() {
        val records = listOf(
            createRecord(sampleId = "exp_norm_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_START", camEvent = "NONE", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_norm_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", userAction = "CAMERA_OPEN_REQUESTED", camEvent = "CAMERA_OPEN_REQUESTED", fgs = true, ts = "2026-09-25T10:00:00.100Z"),
            createRecord(sampleId = "exp_norm_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", fgs = true, ts = "2026-09-25T10:00:00.200Z"),
            createRecord(sampleId = "exp_norm_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAPTURE_SESSION_STARTED", fgs = true, ts = "2026-09-25T10:00:00.300Z"),
            createRecord(sampleId = "exp_norm_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_STOP", camEvent = "NONE", fgs = true, ts = "2026-09-25T10:00:05.000Z"),
            createRecord(sampleId = "exp_norm_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", userAction = "CAMERA_STOP_REQUESTED", camEvent = "CAMERA_STOP_REQUESTED", fgs = true, ts = "2026-09-25T10:00:05.050Z"),
            createRecord(sampleId = "exp_norm_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", fgs = false, ts = "2026-09-25T10:00:05.100Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals(1, sessions.size)
        val s = sessions[0]
        assertEquals("exp_norm_01", s.sessionId)
        assertEquals("NORMAL_FOREGROUND_CAMERA", s.primaryScenarioId)
        assertEquals("USER_INITIATED_FOREGROUND", s.groundTruthContext)
        assertEquals(ReconstructedSession.CameraLifecycleState.OPENED_AND_CLOSED, s.cameraLifecycle)
        assertTrue("Normal foreground lifecycle must be valid", s.isValidLifecycle)
        assertFalse("Should not require review", s.reviewRequired)
        assertFalse("Should not be excluded from ML", s.isExcludedFromMl)
    }

    @Test
    fun `test 2 - background continuation session reconstructs with proper context`() {
        val records = listOf(
            createRecord(sampleId = "exp_bg_01", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_START", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_bg_01", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", fgs = true, ts = "2026-09-25T10:00:00.150Z"),
            createRecord(sampleId = "exp_bg_01", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAPTURE_SESSION_STARTED", fgs = true, ts = "2026-09-25T10:00:00.250Z"),
            createRecord(sampleId = "exp_bg_01", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "USER_INITIATED_BACKGROUND_CONTINUATION", userAction = "APP_ENTERED_BACKGROUND", vis = "BACKGROUND", actState = "STOPPED", fgs = true, ts = "2026-09-25T10:00:03.000Z"),
            createRecord(sampleId = "exp_bg_01", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "USER_INITIATED_BACKGROUND_CONTINUATION", userAction = "APP_RETURNED_FOREGROUND", vis = "FOREGROUND", actState = "RESUMED", fgs = true, ts = "2026-09-25T10:00:08.000Z"),
            createRecord(sampleId = "exp_bg_01", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "CAMERA_SESSION_CLOSED", userAction = "USER_PRESSED_STOP", ts = "2026-09-25T10:00:10.000Z"),
            createRecord(sampleId = "exp_bg_01", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", fgs = false, ts = "2026-09-25T10:00:10.100Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals(1, sessions.size)
        val s = sessions[0]
        assertEquals("BACKGROUND_CAMERA_CONTINUATION", s.primaryScenarioId)
        assertEquals("USER_INITIATED_BACKGROUND_CONTINUATION", s.groundTruthContext)
        assertEquals(ReconstructedSession.CameraLifecycleState.OPENED_AND_CLOSED, s.cameraLifecycle)
        assertTrue(s.isValidLifecycle)
    }

    @Test
    fun `test 3 - permission denied session correctly handles absence of camera`() {
        val records = listOf(
            createRecord(
                sampleId = "exp_denied_01",
                scenario = "PERMISSION_DENIED",
                gt = "PERMISSION_DENIED",
                userAction = "USER_EVALUATED_PERMISSION_DENIED",
                perm = "DENIED",
                camEvent = "NONE",
                fgs = false,
                ts = "2026-09-25T10:00:00.000Z"
            )
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals(1, sessions.size)
        val s = sessions[0]
        assertEquals(ReconstructedSession.CameraLifecycleState.NO_CAMERA, s.cameraLifecycle)
        assertEquals("PERMISSION_DENIED", s.groundTruthContext)
        assertEquals("DENIED", s.permissionState)
        assertTrue(s.isValidLifecycle)
        assertFalse(s.isContradictoryGroundTruth)
    }

    @Test
    fun `test 4 - permission granted no camera session preserves NO_CAMERA state`() {
        val records = listOf(
            createRecord(
                sampleId = "exp_nocam_01",
                scenario = "PERMISSION_GRANTED_NO_CAMERA",
                gt = "NO_CAMERA_ACTIVITY",
                userAction = "USER_EVALUATED_NO_CAMERA",
                perm = "GRANTED",
                camEvent = "NONE",
                fgs = false,
                ts = "2026-09-25T10:00:00.000Z"
            )
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals(1, sessions.size)
        val s = sessions[0]
        assertEquals(ReconstructedSession.CameraLifecycleState.NO_CAMERA, s.cameraLifecycle)
        assertEquals("NO_CAMERA_ACTIVITY", s.groundTruthContext)
        assertEquals("GRANTED", s.permissionState)
        assertTrue(s.isValidLifecycle)
    }

    @Test
    fun `test 5 - ambiguous context observation evaluates without camera activation`() {
        val records = listOf(
            createRecord(
                sampleId = "exp_amb_01",
                scenario = "AMBIGUOUS_CONTEXT",
                gt = "AMBIGUOUS_CONTEXT",
                userAction = "USER_EVALUATED_OBSERVATION",
                perm = "GRANTED",
                camEvent = "NONE",
                fgs = false,
                ts = "2026-09-25T10:00:00.000Z"
            )
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals(1, sessions.size)
        val s = sessions[0]
        assertEquals(ReconstructedSession.CameraLifecycleState.NO_CAMERA, s.cameraLifecycle)
        assertEquals("AMBIGUOUS_CONTEXT", s.groundTruthContext)
        assertTrue(s.isValidLifecycle)
    }

    @Test
    fun `test 6 - automated background trigger session reconstructs with AUTOMATED_TIMER trigger`() {
        val records = listOf(
            createRecord(sampleId = "exp_auto_01", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "AUTOMATED_BACKGROUND_TRIGGER", userAction = "ARM_AUTOMATED_TRIGGER", fgs = true, ts = "2026-09-25T10:00:00.000Z", notes = "trigger=countdown_timer;delay_sec=5"),
            createRecord(sampleId = "exp_auto_01", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "AUTOMATED_BACKGROUND_TRIGGER", userAction = "AUTOMATED_TRIGGER_FIRED", camEvent = "CAMERA_OPENED", fgs = true, vis = "BACKGROUND", actState = "STOPPED", recent = false, ts = "2026-09-25T10:00:05.000Z", notes = "trigger=countdown_timer"),
            createRecord(sampleId = "exp_auto_01", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "AUTOMATED_BACKGROUND_TRIGGER", camEvent = "CAPTURE_SESSION_STARTED", fgs = true, vis = "BACKGROUND", actState = "STOPPED", recent = false, ts = "2026-09-25T10:00:05.100Z"),
            createRecord(sampleId = "exp_auto_01", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "CAMERA_SESSION_CLOSED", userAction = "USER_PRESSED_STOP", ts = "2026-09-25T10:00:10.000Z"),
            createRecord(sampleId = "exp_auto_01", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", fgs = false, ts = "2026-09-25T10:00:10.100Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals(1, sessions.size)
        val s = sessions[0]
        assertEquals("AUTOMATED_BACKGROUND_TRIGGER", s.groundTruthContext)
        assertEquals("AUTOMATED_TIMER", s.triggerMechanism)
        assertFalse("recentUserInteraction must be false for automated background trigger", s.recentUserInteraction ?: true)
        assertTrue(s.isValidLifecycle)
    }

    @Test
    fun `test 7 - duplicate closure telemetry is detected and flagged`() {
        val records = listOf(
            createRecord(sampleId = "exp_dup_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_START", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_dup_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", fgs = true, ts = "2026-09-25T10:00:00.100Z"),
            createRecord(sampleId = "exp_dup_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", userAction = "USER_PRESSED_STOP", ts = "2026-09-25T10:00:05.000Z"),
            createRecord(sampleId = "exp_dup_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", userAction = "CAMERA_STOP_REQUESTED", camEvent = "CAMERA_STOP_REQUESTED", ts = "2026-09-25T10:00:05.050Z"),
            createRecord(sampleId = "exp_dup_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.100Z"),
            // Duplicate stop/close sequence (pre-Phase-3.5.1 behavior)
            createRecord(sampleId = "exp_dup_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", userAction = "CAMERA_STOP_REQUESTED", camEvent = "CAMERA_STOP_REQUESTED", ts = "2026-09-25T10:00:05.150Z"),
            createRecord(sampleId = "exp_dup_01", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.200Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals(1, sessions.size)
        val s = sessions[0]
        assertTrue("Session must be flagged as having duplicates", s.hasDuplicates)
        assertTrue(s.duplicateEventCount >= 2)
        assertTrue(s.reviewRequired)
        assertTrue(s.reviewReasons.any { it.contains("repeated closure") })
    }

    @Test
    fun `test 8 - repeated experiments with same scenario are preserved as distinct sessions`() {
        val session1Records = listOf(
            createRecord(sampleId = "exp_rep1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_START", ts = "2026-09-25T10:00:00.000Z", notes = "rep=1"),
            createRecord(sampleId = "exp_rep1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", fgs = true, ts = "2026-09-25T10:00:00.100Z"),
            createRecord(sampleId = "exp_rep1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z")
        )

        val session2Records = listOf(
            createRecord(sampleId = "exp_rep2", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_START", ts = "2026-09-25T10:05:00.000Z", notes = "rep=2"),
            createRecord(sampleId = "exp_rep2", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", fgs = true, ts = "2026-09-25T10:05:00.100Z"),
            createRecord(sampleId = "exp_rep2", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:05:05.000Z")
        )

        val allRecords = session1Records + session2Records
        val sessions = SessionReconstructor.reconstructSessions(allRecords)

        assertEquals("Both repetitions must be retained as separate logical sessions", 2, sessions.size)
        assertEquals(1, sessions[0].repetition)
        assertEquals(2, sessions[1].repetition)
        assertEquals("exp_rep1", sessions[0].sessionId)
        assertEquals("exp_rep2", sessions[1].sessionId)
    }

    @Test
    fun `test 9 - missing lifecycle closure is flagged as unclosed session`() {
        val records = listOf(
            createRecord(sampleId = "exp_unclosed", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", userAction = "USER_PRESSED_START", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_unclosed", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", fgs = true, ts = "2026-09-25T10:00:00.100Z")
            // Missing stop and closure
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals(1, sessions.size)
        val s = sessions[0]
        assertEquals(ReconstructedSession.CameraLifecycleState.OPENED_UNCLOSED, s.cameraLifecycle)
        assertFalse(s.isValidLifecycle)
        assertTrue(s.lifecycleIssues.any { it.contains("unclosed") })
    }

    @Test
    fun `test 10 - contradictory ground truth in non-camera scenario is flagged and excluded`() {
        // PERMISSION_DENIED scenario with camera actually opened
        val badRecords = listOf(
            createRecord(sampleId = "exp_bad_gt", scenario = "PERMISSION_DENIED", gt = "PERMISSION_DENIED", userAction = "USER_PRESSED_START", perm = "DENIED", camEvent = "CAMERA_OPENED", fgs = true, ts = "2026-09-25T10:00:00.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(badRecords)
        assertEquals(1, sessions.size)
        val s = sessions[0]
        assertTrue(s.isContradictoryGroundTruth)
        assertTrue(s.reviewRequired)
        assertTrue(s.isExcludedFromMl)
        assertNotNull(s.exclusionReason)
    }

    @Test
    fun `test 11 - UNKNOWN telemetry fields are preserved without distortion`() {
        val records = listOf(
            createRecord(
                sampleId = "exp_unknown_test",
                scenario = "AMBIGUOUS_CONTEXT",
                gt = "AMBIGUOUS_CONTEXT",
                screen = "UNKNOWN",
                perm = "UNKNOWN",
                actState = "UNKNOWN",
                vis = "UNKNOWN",
                avail = "UNKNOWN",
                ts = "2026-09-25T10:00:00.000Z"
            )
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        val s = sessions[0]
        assertEquals("UNKNOWN", s.screenState)
        assertEquals("UNKNOWN", s.permissionState)
        assertEquals("UNKNOWN", s.activityLifecycle)
        assertEquals("UNKNOWN", s.appVisibility)
        assertEquals("UNKNOWN", s.cameraAvailability)

        val mlRecords = SessionReconstructor.generateDerivedMlRecords(sessions)
        assertEquals(1, mlRecords.size)
        assertEquals("UNKNOWN", mlRecords[0].f01ScreenStateCode)
        assertEquals("UNKNOWN", mlRecords[0].f02IsScreenInteractive)
        assertEquals("UNKNOWN", mlRecords[0].f03IsDeviceLocked)
        assertEquals("UNKNOWN", mlRecords[0].f04HasCameraPermission)
    }

    @Test
    fun `test 12 - UNVERIFIED permission remains distinct from DENIED`() {
        val records = listOf(
            createRecord(sampleId = "exp_unverified", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", perm = "UNKNOWN", ts = "2026-09-25T10:00:00.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals("UNKNOWN", sessions[0].permissionState)

        val mlRecords = SessionReconstructor.generateDerivedMlRecords(sessions)
        assertEquals("UNKNOWN", mlRecords[0].f04HasCameraPermission)
        assertFalse("Must not convert UNKNOWN to DENIED", mlRecords[0].f04HasCameraPermission == "FALSE")
    }

    @Test
    fun `test 13 - malformed CSV fields throw safe exception in exporter parser`() {
        val malformedCsv = "sample_id,timestamp,scenario_id\n1,2"
        var threw = false
        try {
            ExperimentDataExporter.parseCsv(malformedCsv)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("Parser must reject malformed row with mismatched columns", threw)
    }

    @Test
    fun `test 14 - CSV escaping round-trip preserves special characters and line breaks`() {
        val record = ExperimentRecord(
            sampleId = "exp_escape_01",
            timestamp = "2026-09-25T10:00:00.000Z",
            scenarioId = "NORMAL_FOREGROUND_CAMERA",
            groundTruthContext = "USER_INITIATED_FOREGROUND",
            userAction = "USER_PRESSED_START",
            cameraEvent = "NONE",
            cameraId = "0",
            packageName = "org.cameratestharness",
            cameraPermission = "GRANTED",
            activityState = "RESUMED",
            appVisibility = "FOREGROUND",
            foregroundServiceActive = true,
            foregroundServiceType = "camera",
            screenState = "ON",
            sessionState = "RUNNING",
            sessionDurationMs = "1000",
            recentUserInteraction = true,
            lifecycleEvent = "NONE",
            cameraAvailability = "UNAVAILABLE",
            notes = "Special notes: \"nested quotes\", commas, and\nnewline"
        )

        val csvString = ExperimentDataExporter.exportToCsvString(listOf(record))
        val parsed = ExperimentDataExporter.parseCsv(csvString)
        assertEquals(1, parsed.size)
        assertEquals(record.notes, parsed[0].notes)
    }

    @Test
    fun `test 15 - session boundary detection correctly groups intermixed events by session ID`() {
        val records = listOf(
            createRecord(sampleId = "session_A", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "session_B", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "USER_INITIATED_BACKGROUND_CONTINUATION", ts = "2026-09-25T10:00:01.000Z"),
            createRecord(sampleId = "session_A", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", ts = "2026-09-25T10:00:02.000Z"),
            createRecord(sampleId = "session_B", scenario = "BACKGROUND_CAMERA_CONTINUATION", gt = "CAMERA_SESSION_CLOSED", ts = "2026-09-25T10:00:03.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(records)
        assertEquals(2, sessions.size)
        val sA = sessions.find { it.sessionId == "session_A" }
        val sB = sessions.find { it.sessionId == "session_B" }

        assertNotNull(sA)
        assertNotNull(sB)
        assertEquals(2, sA?.orderedEvents?.size)
        assertEquals(2, sB?.orderedEvents?.size)
    }

    @Test
    fun `test 16 - dataset analysis summary aggregates statistics accurately`() {
        val records = listOf(
            createRecord(sampleId = "s1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "USER_INITIATED_FOREGROUND", camEvent = "CAMERA_OPENED", fgs = true, ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "s1", scenario = "NORMAL_FOREGROUND_CAMERA", gt = "CAMERA_SESSION_CLOSED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z"),
            createRecord(sampleId = "s2", scenario = "PERMISSION_DENIED", gt = "PERMISSION_DENIED", perm = "DENIED", camEvent = "NONE", ts = "2026-09-25T10:01:00.000Z")
        )

        val summary = SessionReconstructor.analyzeDataset(records)
        assertEquals(3, summary.totalRawRows)
        assertEquals(2, summary.totalSessions)
        assertEquals(2, summary.validSessions)
        assertEquals(0, summary.excludedFromMlSessions)
        assertEquals(1, summary.sessionsByScenario["NORMAL_FOREGROUND_CAMERA"])
        assertEquals(1, summary.sessionsByScenario["PERMISSION_DENIED"])
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
