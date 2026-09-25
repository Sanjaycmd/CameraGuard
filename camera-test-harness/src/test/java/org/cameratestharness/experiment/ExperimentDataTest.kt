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
            "AMBIGUOUS_CONTEXT"
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

        // If permission changes to GRANTED afterward, old DENIED records remain intact
        // but new records cannot falsely claim ground truth is PERMISSION_DENIED
        ExperimentLogger.recordEvent(
            userAction = "APP_RESUMED",
            cameraEvent = "NONE",
            cameraPermission = "GRANTED",
            notes = "Permission became granted"
        )

        val updatedRecords = ExperimentLogger.getRecords()
        assertEquals(2, updatedRecords.size)

        // Historical record 0 remains DENIED
        assertEquals("PERMISSION_DENIED", updatedRecords[0].groundTruthContext)
        assertEquals("DENIED", updatedRecords[0].cameraPermission)

        // New record 1 cannot falsely claim PERMISSION_DENIED when permission is GRANTED
        assertNotEquals(
            "Ground truth cannot be PERMISSION_DENIED when permission is GRANTED",
            "PERMISSION_DENIED",
            updatedRecords[1].groundTruthContext
        )
        assertEquals("GRANTED", updatedRecords[1].cameraPermission)
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
}
