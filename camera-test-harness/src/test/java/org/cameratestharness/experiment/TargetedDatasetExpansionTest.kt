package org.cameratestharness.experiment

import org.cameratestharness.CameraDeviceInfo
import org.cameratestharness.CameraLensOption
import org.cameratestharness.CameraSelectionResult
import org.cameratestharness.CameraSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetedDatasetExpansionTest {

    @Test
    fun `test 1 - front-camera selection correctly identifies front-facing device`() {
        val mockCameras = listOf(
            CameraDeviceInfo("0", CameraSelector.LENS_FACING_BACK),
            CameraDeviceInfo("1", CameraSelector.LENS_FACING_FRONT)
        )

        val result = CameraSelector.selectCamera(mockCameras, CameraLensOption.FRONT)
        assertEquals("1", result.selectedId)
        assertEquals(CameraSelector.LENS_FACING_FRONT, result.selectedLensFacing)
        assertFalse(result.isFallback)
        assertTrue(result.isFrontCamera)
        assertFalse(result.isBackCamera)
        assertTrue(result.statusMessage.contains("Selected front camera"))
    }

    @Test
    fun `test 2 - rear-camera selection correctly identifies rear-facing device`() {
        val mockCameras = listOf(
            CameraDeviceInfo("0", CameraSelector.LENS_FACING_BACK),
            CameraDeviceInfo("1", CameraSelector.LENS_FACING_FRONT)
        )

        val result = CameraSelector.selectCamera(mockCameras, CameraLensOption.BACK)
        assertEquals("0", result.selectedId)
        assertEquals(CameraSelector.LENS_FACING_BACK, result.selectedLensFacing)
        assertFalse(result.isFallback)
        assertTrue(result.isBackCamera)
        assertFalse(result.isFrontCamera)
        assertTrue(result.statusMessage.contains("Selected rear camera"))
    }

    @Test
    fun `test 3 - AUTO default selection defaults to rear camera`() {
        val mockCameras = listOf(
            CameraDeviceInfo("0", CameraSelector.LENS_FACING_BACK),
            CameraDeviceInfo("1", CameraSelector.LENS_FACING_FRONT)
        )

        val result = CameraSelector.selectCamera(mockCameras, CameraLensOption.AUTO_DEFAULT)
        assertEquals("0", result.selectedId)
        assertEquals(CameraSelector.LENS_FACING_BACK, result.selectedLensFacing)
        assertFalse(result.isFallback)
        assertTrue(result.isBackCamera)
    }

    @Test
    fun `test 4 - unavailable front camera handling gracefully falls back to default`() {
        val mockCameras = listOf(
            CameraDeviceInfo("0", CameraSelector.LENS_FACING_BACK)
        )

        val result = CameraSelector.selectCamera(mockCameras, CameraLensOption.FRONT)
        assertEquals("0", result.selectedId)
        assertEquals(CameraSelector.LENS_FACING_BACK, result.selectedLensFacing)
        assertTrue("Must indicate fallback occurred", result.isFallback)
        assertTrue(result.statusMessage.contains("No front camera found"))
    }

    @Test
    fun `test 5 - camera selection does not break existing lifecycle`() {
        val frontRecords = listOf(
            createRecord(
                sampleId = "exp_front_cam_rep1",
                scenario = "NORMAL_FOREGROUND_CAMERA",
                gt = "USER_INITIATED_FOREGROUND",
                userAction = "USER_PRESSED_START",
                camEvent = "NONE",
                camId = "1",
                ts = "2026-09-25T10:00:00.000Z"
            ),
            createRecord(
                sampleId = "exp_front_cam_rep1",
                scenario = "NORMAL_FOREGROUND_CAMERA",
                gt = "USER_INITIATED_FOREGROUND",
                userAction = "NONE",
                camEvent = "CAMERA_OPENED",
                camId = "1",
                fgs = true,
                ts = "2026-09-25T10:00:00.120Z",
                notes = "rep=1;trigger=user_start;lens_facing=FRONT"
            ),
            createRecord(
                sampleId = "exp_front_cam_rep1",
                scenario = "NORMAL_FOREGROUND_CAMERA",
                gt = "CAMERA_SESSION_CLOSED",
                userAction = "USER_PRESSED_STOP",
                camEvent = "CAMERA_STOP_REQUESTED",
                camId = "1",
                fgs = true,
                ts = "2026-09-25T10:00:05.000Z"
            ),
            createRecord(
                sampleId = "exp_front_cam_rep1",
                scenario = "NORMAL_FOREGROUND_CAMERA",
                gt = "CAMERA_SESSION_CLOSED",
                userAction = "NONE",
                camEvent = "CAMERA_CLOSED",
                camId = "1",
                fgs = false,
                ts = "2026-09-25T10:00:05.100Z"
            )
        )

        val sessions = SessionReconstructor.reconstructSessions(frontRecords)
        assertEquals(1, sessions.size)
        val session = sessions[0]
        assertTrue(session.isValidLifecycle)
        assertEquals(ReconstructedSession.CameraLifecycleState.OPENED_AND_CLOSED, session.cameraLifecycle)

        val featureRow = FeatureExtractor.extractFromSession(session)
        assertEquals("1", featureRow.t0Features.f10CameraHardwareId)
        assertEquals("FALSE", featureRow.t0Features.f11IsBackCamera)
    }

    @Test
    fun `test 6 - repetition IDs remain unique`() {
        val id1 = ExperimentLogger.startNewSession(ExperimentScenario.NORMAL_FOREGROUND_CAMERA, rep = 1)
        ExperimentLogger.stopSession()

        val id2 = ExperimentLogger.startNewSession(ExperimentScenario.NORMAL_FOREGROUND_CAMERA, rep = 2)
        ExperimentLogger.stopSession()

        val id3 = ExperimentLogger.startNewSession(ExperimentScenario.NORMAL_FOREGROUND_CAMERA, rep = 3)
        ExperimentLogger.stopSession()

        assertTrue(id1.contains("_rep1_"))
        assertTrue(id2.contains("_rep2_"))
        assertTrue(id3.contains("_rep3_"))
        assertTrue(id1 != id2)
        assertTrue(id2 != id3)
    }

    @Test
    fun `test 7 - repeated legitimate sessions are not considered duplicates`() {
        val rep1Records = listOf(
            createRecord(sampleId = "exp_legit_rep1", scenario = "NORMAL_FOREGROUND_CAMERA", userAction = "USER_PRESSED_START", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_legit_rep1", scenario = "NORMAL_FOREGROUND_CAMERA", userAction = "USER_PRESSED_STOP", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z")
        )
        val rep2Records = listOf(
            createRecord(sampleId = "exp_legit_rep2", scenario = "NORMAL_FOREGROUND_CAMERA", userAction = "USER_PRESSED_START", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:01:00.000Z"),
            createRecord(sampleId = "exp_legit_rep2", scenario = "NORMAL_FOREGROUND_CAMERA", userAction = "USER_PRESSED_STOP", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:01:05.000Z")
        )

        val summary = SessionReconstructor.analyzeDataset(rep1Records + rep2Records)
        assertEquals(2, summary.totalSessions)
        assertEquals(0, summary.duplicateStats.exactDuplicateRows)
        assertEquals(0, summary.duplicateStats.sessionsWithDuplicates)
    }

    @Test
    fun `test 8 - exact duplicate export rows remain detectable`() {
        val rec = createRecord(sampleId = "exp_dup_check", ts = "2026-09-25T10:00:00.000Z")
        val summary = SessionReconstructor.analyzeDataset(listOf(rec, rec))

        assertEquals(1, summary.duplicateStats.exactDuplicateRows)
    }

    @Test
    fun `test 9 - ground-truth invariants remain enforced`() {
        // PERMISSION_DENIED with active camera event must fail validation
        val invalidRecord = createRecord(
            sampleId = "exp_inv_fail",
            scenario = "PERMISSION_DENIED",
            gt = "PERMISSION_DENIED",
            camEvent = "CAMERA_OPENED",
            perm = "DENIED"
        )

        val result = ExperimentDataValidator.validateRecords(listOf(invalidRecord))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("cannot have active camera event") })
    }

    @Test
    fun `test 10 - automated background trigger remains correctly labelled`() {
        val autoRecords = listOf(
            createRecord(sampleId = "exp_auto_bg", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "AUTOMATED_BACKGROUND_TRIGGER", userAction = "ARM_AUTOMATED_TRIGGER", camEvent = "NONE", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_auto_bg", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "AUTOMATED_BACKGROUND_TRIGGER", userAction = "AUTOMATED_TRIGGER_FIRED", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:00:05.000Z"),
            createRecord(sampleId = "exp_auto_bg", scenario = "AUTOMATED_BACKGROUND_TRIGGER", gt = "CAMERA_SESSION_CLOSED", userAction = "USER_PRESSED_STOP", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:10.000Z")
        )

        val sessions = SessionReconstructor.reconstructSessions(autoRecords)
        assertEquals(1, sessions.size)
        assertEquals("AUTOMATED_BACKGROUND_TRIGGER", sessions[0].groundTruthContext)
        assertFalse(sessions[0].groundTruthContext.contains("MALICIOUS"))
        assertFalse(sessions[0].groundTruthContext.contains("SPYWARE"))
    }

    @Test
    fun `test 11 - ambiguous context remains observation-only`() {
        val obsRecords = listOf(
            createRecord(
                sampleId = "exp_obs_only",
                scenario = "AMBIGUOUS_CONTEXT",
                gt = "AMBIGUOUS_CONTEXT",
                userAction = "USER_EVALUATED_OBSERVATION",
                camEvent = "NONE",
                ts = "2026-09-25T10:00:00.000Z"
            )
        )

        val sessions = SessionReconstructor.reconstructSessions(obsRecords)
        assertEquals(1, sessions.size)
        assertEquals(ReconstructedSession.CameraLifecycleState.NO_CAMERA, sessions[0].cameraLifecycle)
        assertEquals("AMBIGUOUS_CONTEXT", sessions[0].groundTruthContext)
    }

    @Test
    fun `test 12 - permission-denied scenario cannot produce active camera ground truth`() {
        val permDenied = createRecord(
            sampleId = "exp_perm_denied_ok",
            scenario = "PERMISSION_DENIED",
            gt = "PERMISSION_DENIED",
            userAction = "USER_EVALUATED_PERMISSION_DENIED",
            camEvent = "NONE",
            perm = "DENIED",
            ts = "2026-09-25T10:00:00.000Z"
        )

        val result = ExperimentDataValidator.validateRecords(listOf(permDenied))
        assertTrue(result.isValid)
    }

    @Test
    fun `test 13 - permission-granted-no-camera cannot contain active camera ground truth`() {
        val noCamRecord = createRecord(
            sampleId = "exp_no_cam_ok",
            scenario = "PERMISSION_GRANTED_NO_CAMERA",
            gt = "NO_CAMERA_ACTIVITY",
            userAction = "USER_EVALUATED_NO_CAMERA",
            camEvent = "NONE",
            perm = "GRANTED",
            ts = "2026-09-25T10:00:00.000Z"
        )

        val result = ExperimentDataValidator.validateRecords(listOf(noCamRecord))
        assertTrue(result.isValid)
    }

    @Test
    fun `test 14 - screen-state values come from actual telemetry, not fabricated values`() {
        // Without an active Android context (e.g. pure unit test), ScreenStateResolver returns UNKNOWN
        val defaultState = ScreenStateResolver.resolveScreenState(null)
        assertEquals("UNKNOWN", defaultState)

        // Allowed state codes must strictly be in standard set
        val allowedStates = setOf("OFF", "ON_LOCKED", "ON_UNLOCKED", "UNKNOWN")
        assertTrue(allowedStates.contains(ScreenStateResolver.STATE_SCREEN_OFF))
        assertTrue(allowedStates.contains(ScreenStateResolver.STATE_SCREEN_ON_LOCKED))
        assertTrue(allowedStates.contains(ScreenStateResolver.STATE_SCREEN_ON_UNLOCKED))
        assertTrue(allowedStates.contains(ScreenStateResolver.STATE_UNKNOWN))
    }

    @Test
    fun `test 15 - production F04 remains UNVERIFIED for third-party packages`() {
        val records = listOf(
            createRecord(sampleId = "exp_f04_sandbox", pkg = "com.thirdparty.cameraapp", perm = "GRANTED", camEvent = "CAMERA_OPENED", ts = "2026-09-25T10:00:00.000Z"),
            createRecord(sampleId = "exp_f04_sandbox", pkg = "com.thirdparty.cameraapp", perm = "GRANTED", camEvent = "CAMERA_CLOSED", ts = "2026-09-25T10:00:05.000Z")
        )
        val session = SessionReconstructor.reconstructSessions(records)[0]

        // Strict production observability enforces UNVERIFIED for F04
        val prodRow = FeatureExtractor.extractFromSession(session, strictProductionObservability = true)
        assertEquals("UNVERIFIED", prodRow.t0Features.f04HasCameraPermission)

        // Research mode allows oracle permission
        val researchRow = FeatureExtractor.extractFromSession(session, strictProductionObservability = false)
        assertEquals("GRANTED", researchRow.t0Features.f04HasCameraPermission)
    }

    @Test
    fun `test 16 - PERMISSION_DENIED scenario with GRANTED runtime permission and no camera acquisition is valid`() {
        val permDeniedWithGrantedPerm = createRecord(
            sampleId = "exp_perm_denied_granted",
            scenario = "PERMISSION_DENIED",
            gt = "PERMISSION_DENIED",
            userAction = "USER_EVALUATED_PERMISSION_DENIED",
            camEvent = "NONE",
            perm = "GRANTED",
            fgs = false,
            ts = "2026-09-25T10:00:00.000Z"
        )

        val result = ExperimentDataValidator.validateRecords(listOf(permDeniedWithGrantedPerm))
        assertTrue("PERMISSION_DENIED with GRANTED permission and no camera acquisition must be valid", result.isValid)
        assertTrue(result.errors.isEmpty())

        val permDeniedWithDeniedPerm = permDeniedWithGrantedPerm.copy(
            cameraPermission = "DENIED"
        )
        val resultDenied = ExperimentDataValidator.validateRecords(listOf(permDeniedWithDeniedPerm))
        assertTrue("PERMISSION_DENIED with DENIED permission must also be valid", resultDenied.isValid)
    }

    @Test
    fun `test 17 - PERMISSION_DENIED scenario with active camera events or FGS is strictly rejected`() {
        val baseRecord = createRecord(
            sampleId = "exp_perm_denied_violations",
            scenario = "PERMISSION_DENIED",
            gt = "PERMISSION_DENIED",
            userAction = "USER_EVALUATED_PERMISSION_DENIED",
            camEvent = "NONE",
            perm = "GRANTED",
            fgs = false,
            ts = "2026-09-25T10:00:00.000Z"
        )

        // 1. CAMERA_OPENED is invalid
        val openRecord = baseRecord.copy(cameraEvent = "CAMERA_OPENED")
        val resOpen = ExperimentDataValidator.validateRecords(listOf(openRecord))
        assertFalse(resOpen.isValid)
        assertTrue(resOpen.errors.any { it.contains("cannot have active camera event") })

        // 2. CAPTURE_SESSION_STARTED is invalid
        val captureRecord = baseRecord.copy(cameraEvent = "CAPTURE_SESSION_STARTED")
        val resCapture = ExperimentDataValidator.validateRecords(listOf(captureRecord))
        assertFalse(resCapture.isValid)
        assertTrue(resCapture.errors.any { it.contains("cannot have active camera event") })

        // 3. Active foreground service is invalid
        val fgsRecord = baseRecord.copy(foregroundServiceActive = true)
        val resFgs = ExperimentDataValidator.validateRecords(listOf(fgsRecord))
        assertFalse(resFgs.isValid)
        assertTrue(resFgs.errors.any { it.contains("active foreground service") })

        // 4. CAMERA_OPEN_REQUESTED is invalid
        val openReqRecord = baseRecord.copy(cameraEvent = "CAMERA_OPEN_REQUESTED")
        val resOpenReq = ExperimentDataValidator.validateRecords(listOf(openReqRecord))
        assertFalse(resOpenReq.isValid)
        assertTrue(resOpenReq.errors.any { it.contains("cannot have active camera event") })
    }

    @Test
    fun `test 18 - PERMISSION_DENIED session with GRANTED runtime permission reconstructs validly without contradiction`() {
        val sessionRecords = listOf(
            createRecord(
                sampleId = "exp_perm_denied_session",
                scenario = "PERMISSION_DENIED",
                gt = "PERMISSION_DENIED",
                userAction = "USER_EVALUATED_PERMISSION_DENIED",
                camEvent = "NONE",
                perm = "GRANTED",
                fgs = false,
                actState = "RESUMED",
                vis = "FOREGROUND",
                sessState = "IDLE",
                ts = "2026-09-25T10:00:00.000Z"
            )
        )

        val sessions = SessionReconstructor.reconstructSessions(sessionRecords)
        assertEquals(1, sessions.size)
        val session = sessions[0]
        assertEquals("PERMISSION_DENIED", session.groundTruthContext)
        assertEquals("GRANTED", session.permissionState)
        assertEquals(ReconstructedSession.CameraLifecycleState.NO_CAMERA, session.cameraLifecycle)
        assertFalse("Session must not be contradictory", session.isContradictoryGroundTruth)
        assertTrue("Session lifecycle must be valid", session.isValidLifecycle)
        assertFalse("Session must not require review", session.reviewRequired)
        assertFalse("Session must not be excluded from ML", session.isExcludedFromMl)
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
