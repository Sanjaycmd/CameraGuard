package org.cameraguard.monitoring

import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.CameraAccessEvent
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.monitoring.telemetry.InferredPackageContext
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 5.3 Deterministic Camera Event Stress Test Suite.
 *
 * Evaluates [CameraAvailabilityTracker] state tracking, deduplication, 
 * session-owner attribution, and ordering under synthetic high-frequency stress.
 */
class CameraStressUnitTest {

    private lateinit var tracker: CameraAvailabilityTracker
    private val recordedEvents = mutableListOf<CameraAccessEvent>()

    @Before
    fun setup() {
        tracker = CameraAvailabilityTracker()
        recordedEvents.clear()
        tracker.onEventDetected = { event ->
            recordedEvents.add(event)
        }
    }

    /**
     * Scenario A Equivalent: Single event cycle (1 Open -> 1 Close).
     * Validates control baseline: exactly 2 events, strict alternating types.
     */
    @Test
    fun testScenarioA_singleEventControl() {
        // Initial baseline callback (suppressed)
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)

        // Session 1: Open -> Close
        tracker.availabilityCallback.onCameraUnavailable("0")
        tracker.availabilityCallback.onCameraAvailable("0")

        assertEquals(2, recordedEvents.size)
        assertEquals(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, recordedEvents[0].rawEventType)
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, recordedEvents[1].rawEventType)
        assertEquals("0", recordedEvents[0].cameraId)
        assertEquals("0", recordedEvents[1].cameraId)
    }

    /**
     * Scenario B Equivalent: Repeated sequential events (5 complete sessions).
     * Validates 10 transitions, strict alternation, 0 duplicates, 0 missed.
     */
    @Test
    fun testScenarioB_repeatedSequential5Sessions() {
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)

        val sessionCount = 5
        var currentTime = 1000000L

        for (i in 1..sessionCount) {
            currentTime += 1000L
            tracker.onCameraAvailabilityUpdate("0", RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, currentTime)
            currentTime += 1500L
            tracker.onCameraAvailabilityUpdate("0", RawCameraEventType.CAMERA_BECAME_AVAILABLE, currentTime)
        }

        assertEquals(sessionCount * 2, recordedEvents.size)

        // Verify strictly alternating event types and chronological ordering
        for (i in 0 until sessionCount) {
            val openEvent = recordedEvents[i * 2]
            val closeEvent = recordedEvents[i * 2 + 1]

            assertEquals(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, openEvent.rawEventType)
            assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, closeEvent.rawEventType)
            assertTrue("Close timestamp must follow open timestamp", closeEvent.timestamp > openEvent.timestamp)
        }
    }

    /**
     * Scenario C Equivalent: Higher-volume sequential events (20 complete sessions).
     * Validates that state cache and event dispatch remain resilient over 40 transitions.
     */
    @Test
    fun testScenarioC_highVolumeSequential20Sessions() {
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)

        val sessionCount = 20
        var currentTime = 2000000L

        for (i in 1..sessionCount) {
            currentTime += 500L
            tracker.onCameraAvailabilityUpdate("0", RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, currentTime)
            currentTime += 800L
            tracker.onCameraAvailabilityUpdate("0", RawCameraEventType.CAMERA_BECAME_AVAILABLE, currentTime)
        }

        assertEquals(40, recordedEvents.size)
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, tracker.getCachedState("0"))

        // Verify all 20 open events and 20 close events
        val openEvents = recordedEvents.filter { it.rawEventType == RawCameraEventType.CAMERA_BECAME_UNAVAILABLE }
        val closeEvents = recordedEvents.filter { it.rawEventType == RawCameraEventType.CAMERA_BECAME_AVAILABLE }
        assertEquals(20, openEvents.size)
        assertEquals(20, closeEvents.size)
    }

    /**
     * Scenario D Equivalent: Rapid open/close transitions (sub-100ms intervals).
     * Validates that high frequency does not cause state corruption or dropped transitions.
     */
    @Test
    fun testScenarioD_rapidTransitions() {
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)

        var currentTime = 3000000L
        val rapidCycles = 10

        // Rapid 50ms intervals
        for (i in 1..rapidCycles) {
            currentTime += 50L
            tracker.onCameraAvailabilityUpdate("0", RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, currentTime)
            currentTime += 50L
            tracker.onCameraAvailabilityUpdate("0", RawCameraEventType.CAMERA_BECAME_AVAILABLE, currentTime)
        }

        assertEquals(rapidCycles * 2, recordedEvents.size)
        for (i in 0 until rapidCycles) {
            assertEquals(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, recordedEvents[i * 2].rawEventType)
            assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, recordedEvents[i * 2 + 1].rawEventType)
        }
    }

    /**
     * Rapid redundant duplicate callbacks stress.
     * Simulates camera HAL glitch firing identical UNAVAILABLE callbacks 5 times in rapid succession.
     * Verifies that exactly 1 event is recorded and all 4 duplicates are suppressed.
     */
    @Test
    fun testRedundantRapidCallbacks_suppressesDuplicates() {
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)

        var currentTime = 4000000L

        // Genuine transition to UNAVAILABLE
        tracker.onCameraAvailabilityUpdate("0", RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, currentTime)
        assertEquals(1, recordedEvents.size)

        // 5 consecutive duplicate callbacks with 10ms intervals
        for (dup in 1..5) {
            currentTime += 10L
            tracker.onCameraAvailabilityUpdate("0", RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, currentTime)
        }

        // Duplicate callbacks must be ignored
        assertEquals(1, recordedEvents.size)

        // Transition back to AVAILABLE
        currentTime += 100L
        tracker.onCameraAvailabilityUpdate("0", RawCameraEventType.CAMERA_BECAME_AVAILABLE, currentTime)
        assertEquals(2, recordedEvents.size)

        // 5 consecutive duplicate AVAILABLE callbacks
        for (dup in 1..5) {
            currentTime += 10L
            tracker.onCameraAvailabilityUpdate("0", RawCameraEventType.CAMERA_BECAME_AVAILABLE, currentTime)
        }
        assertEquals(2, recordedEvents.size)
    }

    /**
     * Concurrent multi-camera interleaved stress.
     * Interleaves rapid camera transitions between Camera "0" (Rear) and Camera "1" (Front).
     * Verifies strict independent state tracking across physical sensor IDs.
     */
    @Test
    fun testMultiCameraInterleavedStress_preservesSensorIndependence() {
        // Baseline for both cameras
        tracker.availabilityCallback.onCameraAvailable("0")
        tracker.availabilityCallback.onCameraAvailable("1")
        assertEquals(0, recordedEvents.size)

        var time = 5000000L

        // Interleaved sequence:
        // 1. Camera 0 opens
        tracker.onCameraAvailabilityUpdate("0", RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, time++)
        // 2. Camera 1 opens
        tracker.onCameraAvailabilityUpdate("1", RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, time++)
        // 3. Camera 0 closes
        tracker.onCameraAvailabilityUpdate("0", RawCameraEventType.CAMERA_BECAME_AVAILABLE, time++)
        // 4. Camera 1 closes
        tracker.onCameraAvailabilityUpdate("1", RawCameraEventType.CAMERA_BECAME_AVAILABLE, time++)

        assertEquals(4, recordedEvents.size)
        assertEquals("0", recordedEvents[0].cameraId)
        assertEquals(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, recordedEvents[0].rawEventType)

        assertEquals("1", recordedEvents[1].cameraId)
        assertEquals(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, recordedEvents[1].rawEventType)

        assertEquals("0", recordedEvents[2].cameraId)
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, recordedEvents[2].rawEventType)

        assertEquals("1", recordedEvents[3].cameraId)
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, recordedEvents[3].rawEventType)

        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, tracker.getCachedState("0"))
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, tracker.getCachedState("1"))
    }
}
