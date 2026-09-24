package org.cameraguard.monitoring

import android.app.usage.UsageEvents
import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.CameraAccessEvent
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.monitoring.telemetry.ContextualInferenceEngine
import org.cameraguard.monitoring.telemetry.UsageEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CameraAvailabilityTrackerTest {

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
     * Requirement 1: initial AVAILABLE callback → no event
     */
    @Test
    fun testInitialAvailableCallback_producesNoEvent() {
        tracker.availabilityCallback.onCameraAvailable("0")

        assertTrue("Initial AVAILABLE callback must NOT create a CameraAccessEvent", recordedEvents.isEmpty())
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, tracker.getCachedState("0"))
    }

    /**
     * Requirement 2: initial UNAVAILABLE callback → no event
     */
    @Test
    fun testInitialUnavailableCallback_producesNoEvent() {
        tracker.availabilityCallback.onCameraUnavailable("0")

        assertTrue("Initial UNAVAILABLE callback must NOT create a CameraAccessEvent", recordedEvents.isEmpty())
        assertEquals(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, tracker.getCachedState("0"))
    }

    /**
     * Requirement 3: AVAILABLE → UNAVAILABLE → one event
     */
    @Test
    fun testAvailableToUnavailable_producesOneEvent() {
        // Initial callback establishes baseline (no event)
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)

        // Genuine transition to UNAVAILABLE
        tracker.availabilityCallback.onCameraUnavailable("0")

        assertEquals(1, recordedEvents.size)
        val event = recordedEvents[0]
        assertEquals(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, event.rawEventType)
        assertEquals("0", event.cameraId)
        assertEquals(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, tracker.getCachedState("0"))
    }

    /**
     * Requirement 4: UNAVAILABLE → AVAILABLE → one event
     */
    @Test
    fun testUnavailableToAvailable_producesOneEvent() {
        // Initial callback establishes baseline (no event)
        tracker.availabilityCallback.onCameraUnavailable("0")
        assertEquals(0, recordedEvents.size)

        // Genuine transition to AVAILABLE
        tracker.availabilityCallback.onCameraAvailable("0")

        assertEquals(1, recordedEvents.size)
        val event = recordedEvents[0]
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, event.rawEventType)
        assertEquals("0", event.cameraId)
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, tracker.getCachedState("0"))
    }

    /**
     * Requirement 5: duplicate same-state callback → no additional event
     */
    @Test
    fun testDuplicateSameStateCallback_producesNoAdditionalEvent() {
        // 1. Initial AVAILABLE -> establishes baseline, 0 events
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)

        // 2. Duplicate AVAILABLE -> ignored, still 0 events
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)

        // 3. Transition to UNAVAILABLE -> 1 event
        tracker.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)

        // 4. Duplicate UNAVAILABLE calls -> ignored, still 1 event
        tracker.availabilityCallback.onCameraUnavailable("0")
        tracker.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)

        // 5. Transition to AVAILABLE -> 2 events
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(2, recordedEvents.size)

        // 6. Duplicate AVAILABLE -> ignored, still 2 events
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(2, recordedEvents.size)
    }

    /**
     * Requirement: Clear/reset state cache when tracking stopped so future start re-establishes baseline.
     */
    @Test
    fun testResetOrStopTracking_clearsStateCache() {
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, tracker.getCachedState("0"))

        // Stopping tracking clears cache
        tracker.stopTracking()
        assertNull(tracker.getCachedState("0"))

        // Next callback after stop/restart re-establishes baseline without creating an event
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, tracker.getCachedState("0"))
    }

    /**
     * Requirement: Multi-camera independence.
     */
    @Test
    fun testMultipleCameras_trackedIndependently() {
        // Camera 0 initial AVAILABLE -> baseline (0 events)
        tracker.availabilityCallback.onCameraAvailable("0")
        // Camera 1 initial UNAVAILABLE -> baseline (0 events)
        tracker.availabilityCallback.onCameraUnavailable("1")

        assertEquals(0, recordedEvents.size)
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, tracker.getCachedState("0"))
        assertEquals(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, tracker.getCachedState("1"))

        // Camera 0 transitions to UNAVAILABLE -> 1 event for camera 0
        tracker.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)
        assertEquals("0", recordedEvents[0].cameraId)
        assertEquals(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, recordedEvents[0].rawEventType)

        // Camera 1 transitions to AVAILABLE -> 1 event for camera 1 (total 2)
        tracker.availabilityCallback.onCameraAvailable("1")
        assertEquals(2, recordedEvents.size)
        assertEquals("1", recordedEvents[1].cameraId)
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, recordedEvents[1].rawEventType)
    }

    /**
     * Requirement: UsageStats fallback creates one unified event when CameraAvailabilityCallback is absent.
     */
    @Test
    fun testUsageStatsFallback_createsOneUnifiedEventWhenAvailabilityCallbackAbsent() {
        val usageEvents = listOf(
            UsageEventRecord(
                packageName = "com.android.camera",
                timestamp = 100_000L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                className = "com.android.camera.CameraActivity"
            )
        )
        val inferenceEngine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> usageEvents },
            customPermissionChecker = { true }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = inferenceEngine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }
        trackerWithEngine.isRegistered = true
        trackerWithEngine.lastHandledUsageTimestamp = 50_000L

        val handled = trackerWithEngine.checkUsageStatsFallback(100_500L)

        assertTrue("Fallback must return true when camera app is detected", handled)
        assertEquals(1, recordedEvents.size)

        val event = recordedEvents[0]
        assertEquals("com.android.camera", event.inferredPackageName)
        assertEquals(InferenceMethod.USAGE_STATS_FALLBACK, event.inferenceMethod)
        assertEquals(InferenceConfidence.HIGH, event.packageInferenceConfidence)
        assertEquals(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, event.rawEventType)
        assertNull(event.cameraId)
        assertEquals(100_000L, event.timestamp)
        assertEquals(500L, event.detectionLatencyMs)
        assertEquals(true, event.candidateHasCameraPermission)
    }

    /**
     * Requirement: UsageStats fallback is suppressed when CameraAvailabilityCallback already fired recently.
     */
    @Test
    fun testUsageStatsFallback_suppressedWhenAvailabilityTransitionRecentlyOccurred() {
        val usageEvents = listOf(
            UsageEventRecord(
                packageName = "com.android.camera",
                timestamp = 100_000L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                className = "com.android.camera.CameraActivity"
            )
        )
        val inferenceEngine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> usageEvents },
            customPermissionChecker = { true }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = inferenceEngine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }
        trackerWithEngine.isRegistered = true
        trackerWithEngine.lastHandledUsageTimestamp = 50_000L

        // Availability callback transition occurred 200ms ago (within 3000ms window)
        trackerWithEngine.lastAvailabilityTransitionTime = 100_300L

        val handled = trackerWithEngine.checkUsageStatsFallback(100_500L)

        assertFalse("Fallback must be suppressed when availability callback fired recently", handled)
        assertTrue("No duplicate event must be recorded", recordedEvents.isEmpty())
        assertEquals(100_000L, trackerWithEngine.lastHandledUsageTimestamp)
    }

    /**
     * Requirement: UsageStats fallback is suppressed when hardware camera is currently unavailable.
     */
    @Test
    fun testUsageStatsFallback_suppressedWhenHardwareCameraAlreadyUnavailable() {
        val usageEvents = listOf(
            UsageEventRecord(
                packageName = "com.android.camera",
                timestamp = 100_000L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                className = "com.android.camera.CameraActivity"
            )
        )
        val inferenceEngine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> usageEvents },
            customPermissionChecker = { true }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = inferenceEngine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }
        trackerWithEngine.isRegistered = true
        trackerWithEngine.lastHandledUsageTimestamp = 50_000L

        // Establish baseline and put camera 0 in UNAVAILABLE state
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)

        // Reset transition time to simulate long camera session (> 3000ms earlier)
        trackerWithEngine.lastAvailabilityTransitionTime = 50_000L

        // Fallback check at 100_500L
        val handled = trackerWithEngine.checkUsageStatsFallback(100_500L)

        assertFalse("Fallback must be suppressed while hardware camera is already unavailable", handled)
        assertEquals("No second event should be created", 1, recordedEvents.size)
    }

    /**
     * Requirement: Multiple polling cycles for the same foreground launch must produce ONLY ONE event.
     */
    @Test
    fun testUsageStatsFallback_multiplePollCyclesDoNotProduceDuplicateEvents() {
        val usageEvents = listOf(
            UsageEventRecord(
                packageName = "com.android.camera",
                timestamp = 100_000L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                className = "com.android.camera.CameraActivity"
            )
        )
        val inferenceEngine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> usageEvents },
            customPermissionChecker = { true }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = inferenceEngine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }
        trackerWithEngine.isRegistered = true
        trackerWithEngine.lastHandledUsageTimestamp = 50_000L

        // First poll cycle (e.g. 500ms after launch)
        val handled1 = trackerWithEngine.checkUsageStatsFallback(100_500L)
        assertTrue(handled1)
        assertEquals(1, recordedEvents.size)

        // Second poll cycle (1500ms after launch, same foreground activity resumed timestamp)
        val handled2 = trackerWithEngine.checkUsageStatsFallback(101_500L)
        assertFalse("Second poll cycle must not trigger another event for same launch", handled2)
        assertEquals(1, recordedEvents.size)

        // Third poll cycle (2500ms after launch)
        val handled3 = trackerWithEngine.checkUsageStatsFallback(102_500L)
        assertFalse("Third poll cycle must not trigger another event for same launch", handled3)
        assertEquals(1, recordedEvents.size)
    }

    /**
     * Requirement: Bidirectional deduplication - handleCameraTransition suppresses availability callback
     * if UsageStats fallback created an event for the same access within 3000ms.
     */
    @Test
    fun testUsageStatsFallback_bidirectionalDeduplicationSuppressesAvailabilityCallback() {
        val usageEvents = listOf(
            UsageEventRecord(
                packageName = "com.android.camera",
                timestamp = 100_000L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                className = "com.android.camera.CameraActivity"
            )
        )
        val inferenceEngine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> usageEvents },
            customPermissionChecker = { true }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = inferenceEngine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }
        trackerWithEngine.isRegistered = true
        trackerWithEngine.lastHandledUsageTimestamp = 50_000L

        // 1. Establish baseline for camera 0
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)

        // 2. Fallback detects camera launch at current time = 100_100L
        val handled = trackerWithEngine.checkUsageStatsFallback(100_100L)
        assertTrue(handled)
        assertEquals(1, recordedEvents.size)
        assertEquals(InferenceMethod.USAGE_STATS_FALLBACK, recordedEvents[0].inferenceMethod)

        // 3. Late availability callback arrives 300ms after launch (within 3000ms window)
        trackerWithEngine.onCameraAvailabilityUpdate("0", RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, currentTime = 100_400L)

        // 4. Verification: Callback event was suppressed by bidirectional deduplication
        assertEquals("Callback event must be suppressed to avoid duplicate", 1, recordedEvents.size)
    }

    /**
     * Requirement: Check fallback returns false when tracker is not registered.
     */
    @Test
    fun testUsageStatsFallback_returnsFalseWhenNotRegistered() {
        val usageEvents = listOf(
            UsageEventRecord(
                packageName = "com.android.camera",
                timestamp = 100_000L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                className = "com.android.camera.CameraActivity"
            )
        )
        val inferenceEngine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> usageEvents }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = inferenceEngine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }
        trackerWithEngine.isRegistered = false

        val handled = trackerWithEngine.checkUsageStatsFallback(100_500L)
        assertFalse(handled)
        assertTrue(recordedEvents.isEmpty())
    }

    /**
     * Test A: Google camera session
     * UNAVAILABLE → Google
     * AVAILABLE → Google
     */
    @Test
    fun testClosureAttribution_googleCameraSession() {
        val googlePackage = "com.google.android.googlequicksearchbox"
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ ->
                listOf(
                    UsageEventRecord(
                        packageName = googlePackage,
                        timestamp = System.currentTimeMillis() - 500L,
                        eventType = UsageEvents.Event.ACTIVITY_RESUMED
                    )
                )
            },
            customPermissionChecker = { true }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = engine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }

        // Baseline
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)

        // UNAVAILABLE
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)
        assertEquals(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, recordedEvents[0].rawEventType)
        assertEquals(googlePackage, recordedEvents[0].inferredPackageName)
        assertEquals(googlePackage, trackerWithEngine.getActiveSessionOwner("0")?.packageName)

        // AVAILABLE
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(2, recordedEvents.size)
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, recordedEvents[1].rawEventType)
        assertEquals(googlePackage, recordedEvents[1].inferredPackageName)
        assertNull(trackerWithEngine.getActiveSessionOwner("0"))
    }

    /**
     * Test B: WhatsApp camera session
     * UNAVAILABLE → WhatsApp
     * AVAILABLE → WhatsApp
     */
    @Test
    fun testClosureAttribution_whatsAppCameraSession() {
        val whatsappPackage = "com.whatsapp"
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ ->
                listOf(
                    UsageEventRecord(
                        packageName = whatsappPackage,
                        timestamp = System.currentTimeMillis() - 300L,
                        eventType = UsageEvents.Event.ACTIVITY_RESUMED
                    )
                )
            },
            customPermissionChecker = { true }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = engine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }

        // Baseline
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")

        // UNAVAILABLE
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)
        assertEquals(whatsappPackage, recordedEvents[0].inferredPackageName)
        assertEquals(whatsappPackage, trackerWithEngine.getActiveSessionOwner("0")?.packageName)

        // AVAILABLE
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(2, recordedEvents.size)
        assertEquals(whatsappPackage, recordedEvents[1].inferredPackageName)
        assertNull(trackerWithEngine.getActiveSessionOwner("0"))
    }

    /**
     * Test C: Camera closes and Launcher becomes foreground
     * closure must still be attributed to the previous camera-session owner, NOT Launcher.
     */
    @Test
    fun testClosureAttribution_launcherForegroundAtClosure_stillAttributedToPreviousOwner() {
        val googlePackage = "com.google.android.googlequicksearchbox"
        val launcherPackage = "com.android.launcher3"
        val dynamicEvents = mutableListOf<UsageEventRecord>()

        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> dynamicEvents.toList() },
            customPermissionChecker = { true }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = engine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }

        // Baseline
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")

        // Google is foreground when camera opens
        val openTime = System.currentTimeMillis()
        dynamicEvents.add(
            UsageEventRecord(
                packageName = googlePackage,
                timestamp = openTime - 200L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )

        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)
        assertEquals(googlePackage, recordedEvents[0].inferredPackageName)

        // User returns to Home screen (Launcher becomes the newest resumed app in UsageStats)
        val closeTime = System.currentTimeMillis() + 1000L
        dynamicEvents.add(
            UsageEventRecord(
                packageName = launcherPackage,
                timestamp = closeTime - 100L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )

        // Camera closes
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(2, recordedEvents.size)
        val closureEvent = recordedEvents[1]
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, closureEvent.rawEventType)
        assertEquals(
            "Closure must be attributed to the session owner (Google), NOT Launcher",
            googlePackage,
            closureEvent.inferredPackageName
        )
        assertEquals(AccessClassification.EXPECTED, closureEvent.classification)
        assertNull(trackerWithEngine.getActiveSessionOwner("0"))
    }

    /**
     * Test D: No known session owner
     * closure must not invent an application.
     */
    @Test
    fun testClosureAttribution_noKnownSessionOwner_doesNotInventApplication() {
        val dynamicEvents = mutableListOf<UsageEventRecord>()
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> dynamicEvents.toList() }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = engine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }

        // Baseline
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")

        // Camera opens with NO known app in UsageStats (empty events)
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)
        assertNull(recordedEvents[0].inferredPackageName)
        assertNull(trackerWithEngine.getActiveSessionOwner("0"))

        // An app opens while camera was open (e.g. Launcher or Chrome)
        dynamicEvents.add(
            UsageEventRecord(
                packageName = "com.android.launcher3",
                timestamp = System.currentTimeMillis(),
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )

        // Camera closes
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(2, recordedEvents.size)
        val closureEvent = recordedEvents[1]
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, closureEvent.rawEventType)
        assertNull("Closure must not invent an application when no session owner is known", closureEvent.inferredPackageName)
        assertEquals(InferenceConfidence.NONE, closureEvent.packageInferenceConfidence)
        assertEquals(InferenceMethod.NONE, closureEvent.inferenceMethod)
    }

    /**
     * Test E: Two consecutive camera sessions with different applications
     * each closure must use the correct corresponding owner.
     */
    @Test
    fun testClosureAttribution_consecutiveSessions_eachClosureUsesCorrectOwner() {
        val googlePackage = "com.google.android.googlequicksearchbox"
        val whatsappPackage = "com.whatsapp"
        val dynamicEvents = mutableListOf<UsageEventRecord>()

        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> dynamicEvents.toList() },
            customPermissionChecker = { true }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = engine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }

        // Baseline
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")

        // --- Session 1: Google ---
        dynamicEvents.clear()
        dynamicEvents.add(
            UsageEventRecord(
                packageName = googlePackage,
                timestamp = System.currentTimeMillis() - 500L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        // 1. Google opens camera
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)
        assertEquals(googlePackage, recordedEvents[0].inferredPackageName)
        assertEquals(googlePackage, trackerWithEngine.getActiveSessionOwner("0")?.packageName)

        // 2. Google closes camera
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(2, recordedEvents.size)
        assertEquals(googlePackage, recordedEvents[1].inferredPackageName)
        assertNull(trackerWithEngine.getActiveSessionOwner("0"))

        // --- Session 2: WhatsApp ---
        dynamicEvents.clear()
        dynamicEvents.add(
            UsageEventRecord(
                packageName = whatsappPackage,
                timestamp = System.currentTimeMillis() - 200L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        // 3. WhatsApp opens camera
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(3, recordedEvents.size)
        assertEquals(whatsappPackage, recordedEvents[2].inferredPackageName)
        assertEquals(whatsappPackage, trackerWithEngine.getActiveSessionOwner("0")?.packageName)

        // 4. WhatsApp closes camera
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(4, recordedEvents.size)
        assertEquals(whatsappPackage, recordedEvents[3].inferredPackageName)
        assertNull(trackerWithEngine.getActiveSessionOwner("0"))
    }
}
