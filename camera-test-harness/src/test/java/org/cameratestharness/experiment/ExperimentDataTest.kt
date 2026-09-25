package org.cameratestharness.experiment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExperimentDataTest {

    @Before
    fun setUp() {
        ExperimentLogger.clearSession()
    }

    @Test
    fun `test 1 - scenario IDs are stable`() {
        val expectedIds = listOf(
            "NORMAL_FOREGROUND_CAMERA",
            "BACKGROUND_CAMERA_CONTINUATION",
            "CAMERA_START_STOP",
            "PERMISSION_DENIED",
            "PERMISSION_GRANTED_NO_CAMERA",
            "CAMERA_SESSION_CLOSED",
            "AMBIGUOUS_CONTEXT",
            "AUTOMATED_BACKGROUND_TRIGGER"
        )
        val actualIds = ExperimentScenario.entries.map { it.id }
        assertEquals(expectedIds, actualIds)

        for (id in expectedIds) {
            val scenario = ExperimentScenario.fromId(id)
            assertNotNull("Scenario should be resolved for $id", scenario)
            assertEquals(id, scenario?.id)
        }
    }

    @Test
    fun `test 2 - new experiment receives unique session ID`() {
        val session1 = ExperimentLogger.startNewSession(ExperimentScenario.NORMAL_FOREGROUND_CAMERA)
        val session2 = ExperimentLogger.startNewSession(ExperimentScenario.BACKGROUND_CAMERA_CONTINUATION)

        assertNotNull(session1)
        assertNotNull(session2)
        assertNotEquals("Each session must have a unique ID", session1, session2)
        assertTrue(session1.startsWith("exp_"))
        assertTrue(session2.startsWith("exp_"))
    }

    @Test
    fun `test 3 - records preserve session ID`() {
        val session = ExperimentLogger.startNewSession(ExperimentScenario.CAMERA_START_STOP)

        ExperimentLogger.recordEvent(
            userAction = "USER_PRESSED_START",
            cameraEvent = "CAMERA_OPENED",
            cameraId = "0"
        )
        ExperimentLogger.recordEvent(
            userAction = "USER_PRESSED_STOP",
            cameraEvent = "NONE"
        )
        ExperimentLogger.recordEvent(
            userAction = "CAMERA_STOP_REQUESTED",
            cameraEvent = "CAMERA_STOP_REQUESTED",
            cameraId = "0"
        )
        ExperimentLogger.recordEvent(
            cameraEvent = "CAMERA_CLOSED",
            cameraId = "0"
        )

        val records = ExperimentLogger.getRecords()
        assertEquals(4, records.size)
        for (record in records) {
            assertEquals(session, record.sampleId)
        }
    }

    @Test
    fun `test 4 - CSV header is deterministic`() {
        val expectedHeader = "sample_id,timestamp,scenario_id,ground_truth_context,user_action,camera_event,camera_id,package_name,camera_permission,activity_state,app_visibility,foreground_service_active,foreground_service_type,screen_state,session_state,session_duration_ms,recent_user_interaction,lifecycle_event,camera_availability,notes"
        assertEquals(expectedHeader, ExperimentRecord.CSV_HEADER)

        val csv = ExperimentDataExporter.exportToCsvString(emptyList())
        val firstLine = csv.lines().first()
        assertEquals(expectedHeader, firstLine)
    }

    @Test
    fun `test 5 - CSV escaping works correctly with commas quotes and newlines`() {
        val record = ExperimentRecord(
            sampleId = "exp_test",
            timestamp = "2026-09-25T12:00:00.000Z",
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
            sessionDurationMs = "1500",
            recentUserInteraction = true,
            lifecycleEvent = "NONE",
            cameraAvailability = "UNAVAILABLE",
            notes = "Note with, comma and \"quotes\" and \nnewline"
        )

        val csvRow = record.toCsvRow()
        assertTrue("Csv row should contain escaped quotes", csvRow.contains("\"Note with, comma and \"\"quotes\"\" and \nnewline\""))

        val fullCsv = "${ExperimentRecord.CSV_HEADER}\n$csvRow"
        val parsed = ExperimentDataExporter.parseCsv(fullCsv)
        assertEquals(1, parsed.size)
        assertEquals(record.notes, parsed[0].notes)
    }

    @Test
    fun `test 6 - UNKNOWN and UNAVAILABLE values are preserved`() {
        val record = ExperimentRecord(
            sampleId = "exp_unknown",
            timestamp = "UNKNOWN",
            scenarioId = "AMBIGUOUS_CONTEXT",
            groundTruthContext = "AMBIGUOUS_CONTEXT",
            userAction = "NONE",
            cameraEvent = "NONE",
            cameraId = "NONE",
            cameraPermission = "UNKNOWN",
            activityState = "UNKNOWN",
            appVisibility = "UNKNOWN",
            foregroundServiceActive = false,
            foregroundServiceType = "none",
            screenState = "UNKNOWN",
            sessionState = "UNKNOWN",
            sessionDurationMs = "UNKNOWN",
            recentUserInteraction = false,
            lifecycleEvent = "NONE",
            cameraAvailability = "UNKNOWN",
            notes = "UNAVAILABLE"
        )

        val csv = ExperimentDataExporter.exportToCsvString(listOf(record))
        val parsed = ExperimentDataExporter.parseCsv(csv)
        assertEquals(1, parsed.size)
        assertEquals("UNKNOWN", parsed[0].screenState)
        assertEquals("UNKNOWN", parsed[0].sessionDurationMs)
        assertEquals("UNAVAILABLE", parsed[0].notes)
    }

    @Test
    fun `test 7 - single stop produces one closure`() {
        val session = ExperimentLogger.startNewSession(ExperimentScenario.CAMERA_START_STOP)

        // Session start
        ExperimentLogger.recordEvent(userAction = "USER_PRESSED_START", cameraEvent = "CAMERA_OPEN_REQUESTED")
        ExperimentLogger.recordEvent(cameraEvent = "CAMERA_OPENED", cameraId = "0")
        ExperimentLogger.recordEvent(cameraEvent = "CAPTURE_SESSION_STARTED", cameraId = "0")

        // Single STOP sequence
        ExperimentLogger.recordEvent(userAction = "USER_PRESSED_STOP")
        ExperimentLogger.recordEvent(userAction = "CAMERA_STOP_REQUESTED", cameraEvent = "CAMERA_STOP_REQUESTED", cameraId = "0")
        ExperimentLogger.recordEvent(cameraEvent = "CAMERA_CLOSED", cameraId = "0")

        val records = ExperimentLogger.getRecords()
        val stopRequestedCount = records.count { it.cameraEvent == "CAMERA_STOP_REQUESTED" }
        val closedCount = records.count { it.cameraEvent == "CAMERA_CLOSED" }
        val userStopCount = records.count { it.userAction == "USER_PRESSED_STOP" }

        assertEquals("Exactly one USER_PRESSED_STOP must be logged", 1, userStopCount)
        assertEquals("Exactly one CAMERA_STOP_REQUESTED must be logged", 1, stopRequestedCount)
        assertEquals("Exactly one CAMERA_CLOSED must be logged", 1, closedCount)
        assertEquals(ExperimentLogger.SessionLifecycle.CLOSED, ExperimentLogger.getSessionLifecycle())
    }

    @Test
    fun `test 8 - repeated stop is idempotent`() {
        val session = ExperimentLogger.startNewSession(ExperimentScenario.NORMAL_FOREGROUND_CAMERA)

        // Start
        ExperimentLogger.recordEvent(userAction = "USER_PRESSED_START", cameraEvent = "CAMERA_OPEN_REQUESTED")
        ExperimentLogger.recordEvent(cameraEvent = "CAMERA_OPENED", cameraId = "0")

        // First STOP
        ExperimentLogger.recordEvent(userAction = "USER_PRESSED_STOP")
        ExperimentLogger.recordEvent(userAction = "CAMERA_STOP_REQUESTED", cameraEvent = "CAMERA_STOP_REQUESTED", cameraId = "0")
        ExperimentLogger.recordEvent(cameraEvent = "CAMERA_CLOSED", cameraId = "0")

        // Repeated STOP calls, service destruction, and cleanup callbacks
        ExperimentLogger.recordEvent(userAction = "USER_PRESSED_STOP")
        ExperimentLogger.recordEvent(userAction = "CAMERA_STOP_REQUESTED", cameraEvent = "CAMERA_STOP_REQUESTED", cameraId = "0")
        ExperimentLogger.recordEvent(cameraEvent = "CAMERA_CLOSED", cameraId = "0")
        ExperimentLogger.recordEvent(userAction = "CAMERA_STOP_REQUESTED", cameraEvent = "CAMERA_STOP_REQUESTED", cameraId = "0")
        ExperimentLogger.recordEvent(cameraEvent = "CAMERA_CLOSED", cameraId = "0")

        val records = ExperimentLogger.getRecords()
        val stopRequestedCount = records.count { it.cameraEvent == "CAMERA_STOP_REQUESTED" }
        val closedCount = records.count { it.cameraEvent == "CAMERA_CLOSED" }
        val userStopCount = records.count { it.userAction == "USER_PRESSED_STOP" }

        assertEquals("Repeated stop must result in exactly 1 USER_PRESSED_STOP", 1, userStopCount)
        assertEquals("Repeated stop must result in exactly 1 CAMERA_STOP_REQUESTED", 1, stopRequestedCount)
        assertEquals("Repeated stop must result in exactly 1 CAMERA_CLOSED", 1, closedCount)
    }

    @Test
    fun `test 9 - new session after closure creates independent sessions with single closures`() {
        // Session A
        val sessionA = ExperimentLogger.startNewSession(ExperimentScenario.CAMERA_START_STOP)
        ExperimentLogger.recordEvent(userAction = "USER_PRESSED_START", cameraEvent = "CAMERA_OPENED", cameraId = "0")
        ExperimentLogger.recordEvent(userAction = "USER_PRESSED_STOP")
        ExperimentLogger.recordEvent(userAction = "CAMERA_STOP_REQUESTED", cameraEvent = "CAMERA_STOP_REQUESTED", cameraId = "0")
        ExperimentLogger.recordEvent(cameraEvent = "CAMERA_CLOSED", cameraId = "0")

        // Session B
        val sessionB = ExperimentLogger.startNewSession(ExperimentScenario.BACKGROUND_CAMERA_CONTINUATION)
        assertNotEquals("Session IDs must differ", sessionA, sessionB)

        ExperimentLogger.recordEvent(userAction = "USER_PRESSED_START", cameraEvent = "CAMERA_OPENED", cameraId = "0")
        ExperimentLogger.recordEvent(userAction = "USER_PRESSED_STOP")
        ExperimentLogger.recordEvent(userAction = "CAMERA_STOP_REQUESTED", cameraEvent = "CAMERA_STOP_REQUESTED", cameraId = "0")
        ExperimentLogger.recordEvent(cameraEvent = "CAMERA_CLOSED", cameraId = "0")

        val records = ExperimentLogger.getRecords()
        val recordsA = records.filter { it.sampleId == sessionA }
        val recordsB = records.filter { it.sampleId == sessionB }

        assertEquals(1, recordsA.count { it.cameraEvent == "CAMERA_STOP_REQUESTED" })
        assertEquals(1, recordsA.count { it.cameraEvent == "CAMERA_CLOSED" })
        assertEquals(1, recordsB.count { it.cameraEvent == "CAMERA_STOP_REQUESTED" })
        assertEquals(1, recordsB.count { it.cameraEvent == "CAMERA_CLOSED" })
    }

    @Test
    fun `test 10 - ambiguous scenario integrity - explicit camera session cannot be labeled AMBIGUOUS_CONTEXT`() {
        // An observation-only scenario: isCameraActivation is false
        val scenario = ExperimentScenario.AMBIGUOUS_CONTEXT
        assertFalse("AMBIGUOUS_CONTEXT must be an observation-only scenario (no camera activation)", scenario.isCameraActivation)

        // Even if an explicit camera session is accidentally reported under AMBIGUOUS_CONTEXT scenario:
        ExperimentLogger.startNewSession(scenario)
        ExperimentLogger.recordEvent(
            userAction = "USER_PRESSED_START",
            cameraEvent = "CAMERA_OPENED",
            cameraId = "0",
            foregroundServiceActive = true
        )

        val records = ExperimentLogger.getRecords()
        assertEquals(1, records.size)
        assertNotEquals(
            "An explicit user camera session must NEVER be labeled AMBIGUOUS_CONTEXT",
            "AMBIGUOUS_CONTEXT",
            records[0].groundTruthContext
        )
        assertEquals("USER_INITIATED_FOREGROUND", records[0].groundTruthContext)

        // But pure observation without camera session produces AMBIGUOUS_CONTEXT
        ExperimentLogger.clearSession()
        ExperimentLogger.startNewSession(scenario)
        ExperimentLogger.recordEvent(
            userAction = "USER_EVALUATED_OBSERVATION",
            cameraEvent = "NONE",
            cameraId = "NONE",
            cameraPermission = "GRANTED",
            foregroundServiceActive = false,
            notes = "Observation-only evaluation"
        )
        val observationRecords = ExperimentLogger.getRecords()
        assertEquals("AMBIGUOUS_CONTEXT", observationRecords[0].groundTruthContext)
        assertEquals("NONE", observationRecords[0].cameraEvent)
    }

    @Test
    fun `test 11 - permission denied integrity`() {
        // When camera permission is DENIED
        val scenario = ExperimentScenario.PERMISSION_DENIED
        assertFalse("PERMISSION_DENIED must not activate camera", scenario.isCameraActivation)

        ExperimentLogger.startNewSession(scenario)
        ExperimentLogger.recordEvent(
            userAction = "USER_EVALUATED_PERMISSION_DENIED",
            cameraEvent = "NONE",
            cameraPermission = "DENIED",
            notes = "Permission Denied evaluated"
        )

        val records = ExperimentLogger.getRecords()
        assertEquals(1, records.size)
        assertEquals("PERMISSION_DENIED", records[0].groundTruthContext)
        assertEquals("DENIED", records[0].cameraPermission)
        assertEquals("NONE", records[0].cameraEvent)

        // If permission is GRANTED and camera acquisition is withheld, ground truth remains PERMISSION_DENIED
        // reflecting the controlled negative evaluation scenario
        ExperimentLogger.recordEvent(
            userAction = "APP_RESUMED",
            cameraEvent = "NONE",
            cameraPermission = "GRANTED",
            notes = "Permission is granted in OS but camera withheld in PERMISSION_DENIED scenario"
        )

        val updatedRecords = ExperimentLogger.getRecords()
        assertEquals(2, updatedRecords.size)

        // Historical record 0 remains DENIED
        assertEquals("PERMISSION_DENIED", updatedRecords[0].groundTruthContext)
        assertEquals("DENIED", updatedRecords[0].cameraPermission)

        // Record 1 maintains PERMISSION_DENIED ground truth with GRANTED runtime permission
        assertEquals("PERMISSION_DENIED", updatedRecords[1].groundTruthContext)
        assertEquals("GRANTED", updatedRecords[1].cameraPermission)

        // But if camera is actually opened, it cannot claim PERMISSION_DENIED ground truth
        ExperimentLogger.recordEvent(
            userAction = "NONE",
            cameraEvent = "CAMERA_OPENED",
            cameraPermission = "GRANTED",
            notes = "Camera opened invalidly"
        )
        val recordsWithActiveCamera = ExperimentLogger.getRecords()
        assertEquals(3, recordsWithActiveCamera.size)
        assertNotEquals("PERMISSION_DENIED", recordsWithActiveCamera[2].groundTruthContext)
    }

    @Test
    fun `test 12 - existing dataset validation and parsing`() {
        // Create sample records covering all valid scenarios
        val testRecords = listOf(
            ExperimentRecord(
                sampleId = "exp_001",
                timestamp = "2026-09-25T10:00:00.000Z",
                scenarioId = "NORMAL_FOREGROUND_CAMERA",
                groundTruthContext = "USER_INITIATED_FOREGROUND",
                userAction = "USER_PRESSED_START",
                cameraEvent = "CAMERA_OPENED",
                cameraId = "0",
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
                cameraAvailability = "UNAVAILABLE"
            ),
            ExperimentRecord(
                sampleId = "exp_002",
                timestamp = "2026-09-25T10:05:00.000Z",
                scenarioId = "PERMISSION_DENIED",
                groundTruthContext = "PERMISSION_DENIED",
                userAction = "USER_EVALUATED_PERMISSION_DENIED",
                cameraEvent = "NONE",
                cameraId = "NONE",
                cameraPermission = "DENIED",
                activityState = "RESUMED",
                appVisibility = "FOREGROUND",
                foregroundServiceActive = false,
                foregroundServiceType = "none",
                screenState = "ON",
                sessionState = "IDLE",
                sessionDurationMs = "0",
                recentUserInteraction = true,
                lifecycleEvent = "NONE",
                cameraAvailability = "AVAILABLE"
            ),
            ExperimentRecord(
                sampleId = "exp_003",
                timestamp = "2026-09-25T10:10:00.000Z",
                scenarioId = "AMBIGUOUS_CONTEXT",
                groundTruthContext = "AMBIGUOUS_CONTEXT",
                userAction = "USER_EVALUATED_OBSERVATION",
                cameraEvent = "NONE",
                cameraId = "NONE",
                cameraPermission = "GRANTED",
                activityState = "RESUMED",
                appVisibility = "FOREGROUND",
                foregroundServiceActive = false,
                foregroundServiceType = "none",
                screenState = "UNKNOWN",
                sessionState = "IDLE",
                sessionDurationMs = "0",
                recentUserInteraction = false,
                lifecycleEvent = "NONE",
                cameraAvailability = "UNKNOWN"
            )
        )

        val csv = ExperimentDataExporter.exportToCsvString(testRecords)
        val validation = ExperimentDataValidator.validateCsv(csv)
        assertTrue("Dataset validation must pass for valid records", validation.isValid)
        assertTrue("Validation must have 0 errors", validation.errors.isEmpty())

        val parsed = ExperimentDataExporter.parseCsv(csv)
        assertEquals(3, parsed.size)
        assertEquals("exp_001", parsed[0].sampleId)
        assertEquals("exp_002", parsed[1].sampleId)
        assertEquals("exp_003", parsed[2].sampleId)
    }

    @Test
    fun `test 13 - empty dataset export is handled safely`() {
        val csv = ExperimentDataExporter.exportToCsvString(emptyList())
        val parsed = ExperimentDataExporter.parseCsv(csv)
        assertTrue("Parsing empty CSV should result in empty list", parsed.isEmpty())

        val validation = ExperimentDataValidator.validateRecords(emptyList())
        assertTrue("Empty record list should be valid with warning", validation.isValid)
        assertTrue(validation.warnings.isNotEmpty())
    }

    @Test
    fun `test 14 - logging failure does not crash experiment logic`() {
        ExperimentLogger.clearSession()
        ExperimentLogger.recordEvent(
            userAction = "SAFE_TEST",
            notes = "Testing robustness against special chars: \u0000\t\r\n\"',;:"
        )
        val records = ExperimentLogger.getRecords()
        assertEquals(1, records.size)
        assertEquals("SAFE_TEST", records[0].userAction)
    }

    @Test
    fun `test 15 - data quality validator catches invalid scenarios and durations`() {
        val validRecord = ExperimentRecord(
            sampleId = "exp_valid",
            timestamp = "2026-09-25T12:00:00.000Z",
            scenarioId = "NORMAL_FOREGROUND_CAMERA",
            groundTruthContext = "USER_INITIATED_FOREGROUND",
            userAction = "USER_PRESSED_START",
            cameraEvent = "CAMERA_OPENED",
            cameraId = "0",
            cameraPermission = "GRANTED",
            activityState = "RESUMED",
            appVisibility = "FOREGROUND",
            foregroundServiceActive = true,
            foregroundServiceType = "camera",
            screenState = "ON",
            sessionState = "RUNNING",
            sessionDurationMs = "2500",
            recentUserInteraction = true,
            lifecycleEvent = "NONE",
            cameraAvailability = "UNAVAILABLE"
        )

        val validResult = ExperimentDataValidator.validateRecords(listOf(validRecord))
        assertTrue("Valid record must pass validation", validResult.isValid)
        assertTrue(validResult.errors.isEmpty())

        val invalidRecord = validRecord.copy(
            sampleId = "",
            scenarioId = "INVALID_SCENARIO_NAME",
            groundTruthContext = "INVALID_GROUND_TRUTH",
            sessionDurationMs = "-500"
        )

        val invalidResult = ExperimentDataValidator.validateRecords(listOf(invalidRecord))
        assertFalse("Invalid record must fail validation", invalidResult.isValid)
        assertTrue("Should report at least 4 errors", invalidResult.errors.size >= 4)
    }

    @Test
    fun `test 16 - automated background trigger produces AUTOMATED_BACKGROUND_TRIGGER ground truth and CAMERA_SESSION_CLOSED when stopped`() {
        val scenario = ExperimentScenario.AUTOMATED_BACKGROUND_TRIGGER
        assertEquals("AUTOMATED_BACKGROUND_TRIGGER", scenario.id)
        assertTrue(scenario.isCameraActivation)

        val session = ExperimentLogger.startNewSession(scenario, rep = 1)
        assertTrue(session.contains("_rep1_"))

        // Armed countdown
        ExperimentLogger.recordEvent(
            userAction = "ARM_AUTOMATED_TRIGGER",
            cameraEvent = "NONE",
            activityState = "RESUMED",
            appVisibility = "FOREGROUND",
            foregroundServiceActive = true,
            foregroundServiceType = "camera",
            sessionState = "ARMED"
        )

        // Fired automatically in background
        ExperimentLogger.recordEvent(
            userAction = "AUTOMATED_TRIGGER_FIRED",
            cameraEvent = "CAMERA_OPENED",
            cameraId = "0",
            cameraPermission = "GRANTED",
            activityState = "STOPPED",
            appVisibility = "BACKGROUND",
            foregroundServiceActive = true,
            foregroundServiceType = "camera",
            sessionState = "CAMERA_OPEN",
            cameraAvailability = "UNAVAILABLE",
            recentUserInteraction = false,
            notes = "trigger=countdown_timer;delay_sec=5;Automated background trigger"
        )

        ExperimentLogger.recordEvent(
            userAction = "NONE",
            cameraEvent = "CAPTURE_SESSION_STARTED",
            cameraId = "0",
            cameraPermission = "GRANTED",
            activityState = "STOPPED",
            appVisibility = "BACKGROUND",
            foregroundServiceActive = true,
            foregroundServiceType = "camera",
            sessionState = "RUNNING",
            cameraAvailability = "UNAVAILABLE",
            recentUserInteraction = false
        )

        val activeRecords = ExperimentLogger.getRecords()
        assertEquals(3, activeRecords.size)
        assertEquals("AUTOMATED_BACKGROUND_TRIGGER", activeRecords[0].groundTruthContext)
        assertEquals("AUTOMATED_BACKGROUND_TRIGGER", activeRecords[1].groundTruthContext)
        assertEquals("AUTOMATED_BACKGROUND_TRIGGER", activeRecords[2].groundTruthContext)
        assertFalse("recentUserInteraction must be false for automated background trigger", activeRecords[1].recentUserInteraction)

        // Stop session
        ExperimentLogger.recordEvent(userAction = "USER_PRESSED_STOP")
        ExperimentLogger.recordEvent(userAction = "CAMERA_STOP_REQUESTED", cameraEvent = "CAMERA_STOP_REQUESTED", cameraId = "0")
        ExperimentLogger.recordEvent(cameraEvent = "CAMERA_CLOSED", cameraId = "0")
        ExperimentLogger.stopSession()

        val allRecords = ExperimentLogger.getRecords()
        val closedRecord = allRecords.last()
        assertEquals("CAMERA_SESSION_CLOSED", closedRecord.groundTruthContext)
    }

    @Test
    fun `test 17 - repetition count is tracked in session ID and notes across multiple runs`() {
        ExperimentLogger.setRepetition(1)
        val session1 = ExperimentLogger.startNewSession(ExperimentScenario.NORMAL_FOREGROUND_CAMERA)
        assertTrue("Session 1 must contain rep1", session1.contains("_rep1_"))
        ExperimentLogger.recordEvent(userAction = "USER_PRESSED_START", notes = "First run")
        ExperimentLogger.stopSession()

        ExperimentLogger.incrementRepetition()
        val session2 = ExperimentLogger.startNewSession(ExperimentScenario.NORMAL_FOREGROUND_CAMERA)
        assertTrue("Session 2 must contain rep2", session2.contains("_rep2_"))
        ExperimentLogger.recordEvent(userAction = "USER_PRESSED_START", notes = "Second run")
        ExperimentLogger.stopSession()

        val records = ExperimentLogger.getRecords()
        assertEquals(2, records.size)
        assertEquals(session1, records[0].sampleId)
        assertEquals(session2, records[1].sampleId)
        assertTrue(records[0].notes.contains("rep=1"))
        assertTrue(records[1].notes.contains("rep=2"))
    }

    @Test
    fun `test 18 - ground truth consistency invariants - PERMISSION_DENIED with GRANTED permission is valid when acquisition withheld, but active camera rejected`() {
        val validRecordGranted = ExperimentRecord(
            sampleId = "exp_valid_perm_granted",
            timestamp = "2026-09-25T12:00:00.000Z",
            scenarioId = "PERMISSION_DENIED",
            groundTruthContext = "PERMISSION_DENIED",
            userAction = "USER_EVALUATED_PERMISSION_DENIED",
            cameraEvent = "NONE",
            cameraId = "NONE",
            cameraPermission = "GRANTED",
            activityState = "RESUMED",
            appVisibility = "FOREGROUND",
            foregroundServiceActive = false,
            foregroundServiceType = "none",
            screenState = "ON",
            sessionState = "IDLE",
            sessionDurationMs = "0",
            recentUserInteraction = true,
            lifecycleEvent = "NONE",
            cameraAvailability = "AVAILABLE"
        )

        val validResult = ExperimentDataValidator.validateRecords(listOf(validRecordGranted))
        assertTrue("Validator must accept PERMISSION_DENIED ground truth with GRANTED cameraPermission when acquisition withheld", validResult.isValid)
        assertTrue(validResult.errors.isEmpty())

        val invalidActiveCameraRecord = validRecordGranted.copy(
            cameraEvent = "CAMERA_OPENED"
        )
        val invalidResult = ExperimentDataValidator.validateRecords(listOf(invalidActiveCameraRecord))
        assertFalse("Validator must reject PERMISSION_DENIED ground truth when camera event is active", invalidResult.isValid)
        assertTrue(invalidResult.errors.any { it.contains("cannot have active camera event") })

        val invalidFgsRecord = validRecordGranted.copy(
            foregroundServiceActive = true
        )
        val invalidFgsResult = ExperimentDataValidator.validateRecords(listOf(invalidFgsRecord))
        assertFalse("Validator must reject PERMISSION_DENIED ground truth when foreground service is active", invalidFgsResult.isValid)
        assertTrue(invalidFgsResult.errors.any { it.contains("active foreground service") })
    }

    @Test
    fun `test 19 - ground truth consistency invariants - AMBIGUOUS_CONTEXT with active camera is rejected by validator`() {
        val badRecord = ExperimentRecord(
            sampleId = "exp_invalid_ambiguous",
            timestamp = "2026-09-25T12:00:00.000Z",
            scenarioId = "AMBIGUOUS_CONTEXT",
            groundTruthContext = "AMBIGUOUS_CONTEXT",
            userAction = "NONE",
            cameraEvent = "CAMERA_OPENED",
            cameraId = "0",
            cameraPermission = "GRANTED",
            activityState = "RESUMED",
            appVisibility = "FOREGROUND",
            foregroundServiceActive = true,
            foregroundServiceType = "camera",
            screenState = "ON",
            sessionState = "CAMERA_OPEN",
            sessionDurationMs = "1000",
            recentUserInteraction = false,
            lifecycleEvent = "NONE",
            cameraAvailability = "UNAVAILABLE"
        )

        val result = ExperimentDataValidator.validateRecords(listOf(badRecord))
        assertFalse("Validator must reject AMBIGUOUS_CONTEXT ground truth with active camera session", result.isValid)
        assertTrue(result.errors.any { it.contains("AMBIGUOUS_CONTEXT ground truth cannot have active camera events") })
    }

    @Test
    fun `test 20 - ground truth consistency invariants - AUTOMATED_BACKGROUND_TRIGGER with USER_PRESSED_START is rejected by validator`() {
        val badRecord = ExperimentRecord(
            sampleId = "exp_invalid_auto",
            timestamp = "2026-09-25T12:00:00.000Z",
            scenarioId = "AUTOMATED_BACKGROUND_TRIGGER",
            groundTruthContext = "AUTOMATED_BACKGROUND_TRIGGER",
            userAction = "USER_PRESSED_START",
            cameraEvent = "CAMERA_OPENED",
            cameraId = "0",
            cameraPermission = "GRANTED",
            activityState = "STOPPED",
            appVisibility = "BACKGROUND",
            foregroundServiceActive = true,
            foregroundServiceType = "camera",
            screenState = "ON",
            sessionState = "CAMERA_OPEN",
            sessionDurationMs = "5000",
            recentUserInteraction = false,
            lifecycleEvent = "NONE",
            cameraAvailability = "UNAVAILABLE"
        )

        val result = ExperimentDataValidator.validateRecords(listOf(badRecord))
        assertFalse("Validator must reject AUTOMATED_BACKGROUND_TRIGGER with USER_PRESSED_START", result.isValid)
        assertTrue(result.errors.any { it.contains("AUTOMATED_BACKGROUND_TRIGGER ground truth cannot have explicit user start action") })
    }

    @Test
    fun `test 21 - dataset validator accepts valid expanded 8-scenario dataset`() {
        val validRecords = listOf(
            ExperimentRecord(
                sampleId = "exp_auto_001",
                timestamp = "2026-09-25T12:00:00.000Z",
                scenarioId = "AUTOMATED_BACKGROUND_TRIGGER",
                groundTruthContext = "AUTOMATED_BACKGROUND_TRIGGER",
                userAction = "AUTOMATED_TRIGGER_FIRED",
                cameraEvent = "CAMERA_OPENED",
                cameraId = "0",
                cameraPermission = "GRANTED",
                activityState = "STOPPED",
                appVisibility = "BACKGROUND",
                foregroundServiceActive = true,
                foregroundServiceType = "camera",
                screenState = "ON",
                sessionState = "RUNNING",
                sessionDurationMs = "5200",
                recentUserInteraction = false,
                lifecycleEvent = "NONE",
                cameraAvailability = "UNAVAILABLE",
                notes = "rep=1;trigger=countdown_timer;delay_sec=5"
            ),
            ExperimentRecord(
                sampleId = "exp_nocam_002",
                timestamp = "2026-09-25T12:05:00.000Z",
                scenarioId = "PERMISSION_GRANTED_NO_CAMERA",
                groundTruthContext = "NO_CAMERA_ACTIVITY",
                userAction = "USER_EVALUATED_NO_CAMERA",
                cameraEvent = "NONE",
                cameraId = "NONE",
                cameraPermission = "GRANTED",
                activityState = "RESUMED",
                appVisibility = "FOREGROUND",
                foregroundServiceActive = false,
                foregroundServiceType = "none",
                screenState = "ON",
                sessionState = "IDLE",
                sessionDurationMs = "0",
                recentUserInteraction = true,
                lifecycleEvent = "NONE",
                cameraAvailability = "AVAILABLE",
                notes = "rep=1;trigger=observation"
            )
        )

        val csv = ExperimentDataExporter.exportToCsvString(validRecords)
        val validation = ExperimentDataValidator.validateCsv(csv)
        assertTrue("Dataset validation must pass for valid records in expanded scenarios", validation.isValid)
        assertTrue(validation.errors.isEmpty())
    }
}
