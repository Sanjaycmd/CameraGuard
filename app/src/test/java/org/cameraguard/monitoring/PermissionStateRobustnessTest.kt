package org.cameraguard.monitoring

import android.app.usage.UsageEvents
import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.CameraAccessEvent
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.monitoring.detection.CameraRuleEvaluator
import org.cameraguard.monitoring.telemetry.ContextualInferenceEngine
import org.cameraguard.monitoring.telemetry.UsageEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 5.4 - Permission and State Robustness Unit Tests
 *
 * Verifies CameraGuard's robustness under:
 * 1. Granted permission + valid camera session
 * 2. Denied permission + probe scenario
 * 3. Denied probe does not become confirmed session
 * 4. Permission restored after denial
 * 5. Permission state transition does not leak into next session
 * 6. CameraGuard cannot self-attribute
 * 7. Background caller attribution follows Phase 5.3-A.1 behavior
 * 8. Unknown attribution remains UNKNOWN
 * 9. Lifecycle closure after attribution
 * 10. Restart/startup baseline behavior
 * 11. Duplicate suppression across rapid state transitions
 * 12. State reset after session closure
 */
class PermissionStateRobustnessTest {

    private lateinit var tracker: CameraAvailabilityTracker
    private val recordedEvents = mutableListOf<CameraAccessEvent>()
    private val baseTimestamp: Long get() = System.currentTimeMillis()

    @Before
    fun setUp() {
        tracker = CameraAvailabilityTracker()
        recordedEvents.clear()
        tracker.onEventDetected = { recordedEvents.add(it) }
    }

    /**
     * Requirement 1: Granted permission + valid camera session
     */
    @Test
    fun testGrantedPermission_validCameraSession() {
        val testPackage = "org.cameraguard.adversarytest"
        val events = listOf(
            UsageEventRecord(
                packageName = testPackage,
                timestamp = baseTimestamp - 200L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { it == testPackage }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = engine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }

        // 1. Initial baseline
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)

        // 2. Camera opens
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)
        val openEvent = recordedEvents[0]
        assertEquals(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE, openEvent.rawEventType)
        assertEquals(testPackage, openEvent.inferredPackageName)
        assertEquals(InferenceConfidence.HIGH, openEvent.packageInferenceConfidence)
        assertEquals(testPackage, trackerWithEngine.getActiveSessionOwner("0")?.packageName)

        // 3. Camera closes
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(2, recordedEvents.size)
        val closeEvent = recordedEvents[1]
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, closeEvent.rawEventType)
        assertEquals(testPackage, closeEvent.inferredPackageName)
        assertNull(trackerWithEngine.getActiveSessionOwner("0"))
    }

    /**
     * Requirement 2: Denied permission + probe scenario
     * Unprivileged app triggers a transient probe transition.
     */
    @Test
    fun testDeniedPermission_probeScenario() {
        val unprivilegedPackage = "com.unprivileged.probeapp"
        val events = listOf(
            UsageEventRecord(
                packageName = unprivilegedPackage,
                timestamp = baseTimestamp - 50L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { false } // No permission
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = engine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }

        // Initial baseline
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")

        // Transient probe: unavailable -> available within brief window
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)
        val probeEvent = recordedEvents[0]
        assertEquals(unprivilegedPackage, probeEvent.inferredPackageName)
        assertEquals(InferenceConfidence.HIGH, probeEvent.packageInferenceConfidence)
        assertEquals(AccessClassification.UNEXPECTED, probeEvent.classification)
        assertTrue(probeEvent.classificationExplanation.contains("permission", ignoreCase = true) ||
                   probeEvent.classificationExplanation.contains("rule", ignoreCase = true))

        // Probe concludes immediately
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(2, recordedEvents.size)
        assertNull(trackerWithEngine.getActiveSessionOwner("0"))
    }

    /**
     * Requirement 3: Denied probe does not become confirmed session
     */
    @Test
    fun testDeniedProbe_doesNotBecomeConfirmedSession() {
        val deniedPackage = "org.cameraguard.adversarytest"
        val events = listOf(
            UsageEventRecord(
                packageName = deniedPackage,
                timestamp = baseTimestamp - 80L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { false } // Permission denied
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = engine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }

        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")

        val event = recordedEvents[0]
        // Must NOT be classified as EXPECTED normal capture session
        assertEquals(AccessClassification.UNEXPECTED, event.classification)
        assertEquals("TIER_1_RULE", event.tierUsed)
        assertEquals("UNEXPECTED", event.deterministicResult)
        assertFalse(event.mlInvoked)
    }

    /**
     * Requirement 4: Permission restored after denial
     * Denied probe is followed by permission restored session.
     */
    @Test
    fun testPermissionRestoredAfterDenial() {
        var isPermissionGranted = false
        val testPackage = "org.cameraguard.adversarytest"
        val events = listOf(
            UsageEventRecord(
                packageName = testPackage,
                timestamp = baseTimestamp,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { isPermissionGranted }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = engine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }

        // Baseline
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")

        // 1. Denied attempt (permission = false)
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)
        assertEquals(AccessClassification.UNEXPECTED, recordedEvents[0].classification)
        assertEquals("TIER_1_RULE", recordedEvents[0].tierUsed)

        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(2, recordedEvents.size)

        // 2. Permission restored (granted = true)
        isPermissionGranted = true
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(3, recordedEvents.size)
        val restoredEvent = recordedEvents[2]
        assertEquals(testPackage, restoredEvent.inferredPackageName)
        // With permission granted in foreground, Tier 1 rule allows ML evaluation (EXPECTED)
        assertEquals(AccessClassification.EXPECTED, restoredEvent.classification)

        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(4, recordedEvents.size)
    }

    /**
     * Requirement 5: Permission state transition does not leak into next session
     */
    @Test
    fun testPermissionStateTransition_doesNotLeakIntoNextSession() {
        var callerHasPermission = false
        val callerA = "com.unprivileged.app"
        val callerB = "com.trusted.camera"

        var currentPackage = callerA
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ ->
                listOf(
                    UsageEventRecord(
                        packageName = currentPackage,
                        timestamp = baseTimestamp,
                        eventType = UsageEvents.Event.ACTIVITY_RESUMED
                    )
                )
            },
            customPermissionChecker = { callerHasPermission }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = engine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }

        trackerWithEngine.availabilityCallback.onCameraAvailable("0")

        // Session 1: callerA, no permission -> UNEXPECTED
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)
        assertEquals(callerA, recordedEvents[0].inferredPackageName)
        assertEquals(AccessClassification.UNEXPECTED, recordedEvents[0].classification)
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")

        // Session 2: callerB, has permission -> EXPECTED
        currentPackage = callerB
        callerHasPermission = true
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(3, recordedEvents.size)
        val session2Open = recordedEvents[2]
        assertEquals(callerB, session2Open.inferredPackageName)
        assertEquals(AccessClassification.EXPECTED, session2Open.classification)

        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(4, recordedEvents.size)
        assertEquals(callerB, recordedEvents[3].inferredPackageName)
    }

    /**
     * Requirement 6: CameraGuard cannot self-attribute
     */
    @Test
    fun testCameraGuardCannotSelfAttribute() {
        val testCaller = "org.cameraguard.adversarytest"
        val events = listOf(
            UsageEventRecord(
                packageName = testCaller,
                timestamp = baseTimestamp - 1_000L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            ),
            UsageEventRecord(
                packageName = "org.cameraguard",
                timestamp = baseTimestamp - 50L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { it == testCaller }
        )
        val result = engine.inferForegroundPackage(baseTimestamp)

        assertFalse("Inferred package must never be org.cameraguard", result.packageName == "org.cameraguard")
        assertEquals(testCaller, result.packageName)
        assertEquals(InferenceConfidence.MEDIUM, result.confidence)
    }

    /**
     * Requirement 7: Background caller attribution follows Phase 5.3-A.1 behavior
     */
    @Test
    fun testBackgroundCallerAttribution_followsPhase53A1Behavior() {
        val backgroundCaller = "org.cameraguard.adversarytest"
        val unrelatedForeground = "com.android.calculator2"
        // Capture a stable snapshot: baseTimestamp is a dynamic getter that calls
        // System.currentTimeMillis() on every access. Using it multiple times causes
        // minDelta to drift above the 2000ms MEDIUM/LOW confidence boundary.
        val t = baseTimestamp

        val events = listOf(
            UsageEventRecord(
                packageName = backgroundCaller,
                timestamp = t - 2_000L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            ),
            UsageEventRecord(
                packageName = unrelatedForeground,
                timestamp = t - 150L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { it == backgroundCaller } // Calculator lacks camera permission
        )

        val result = engine.inferForegroundPackage(t)

        // When foreground app lacks camera permission, the transitioning camera-capable app is attributed
        assertEquals(backgroundCaller, result.packageName)
        // minDelta = t - (t - 2000) = exactly 2000ms → MEDIUM confidence threshold (≤2000ms)
        assertEquals(InferenceConfidence.MEDIUM, result.confidence)
        assertEquals(true, result.hasCameraPermission)
    }

    /**
     * Requirement 8: Unknown attribution remains UNKNOWN
     */
    @Test
    fun testUnknownAttribution_remainsUnknown() {
        // No camera-capable package in events
        val events = listOf(
            UsageEventRecord(
                packageName = "org.cameraguard",
                timestamp = baseTimestamp - 100L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { false }
        )

        val result = engine.inferForegroundPackage(baseTimestamp)

        assertNull("Package name must be null when only monitor is active", result.packageName)
        assertEquals(InferenceConfidence.NONE, result.confidence)
        assertEquals(InferenceMethod.NONE, result.method)
        assertNull(result.hasCameraPermission)
    }

    /**
     * Requirement 9: Lifecycle closure after attribution
     */
    @Test
    fun testLifecycleClosure_afterAttribution() {
        val sessionOwner = "com.google.android.GoogleCamera"
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ ->
                listOf(
                    UsageEventRecord(
                        packageName = sessionOwner,
                        timestamp = baseTimestamp - 100L,
                        eventType = UsageEvents.Event.ACTIVITY_RESUMED
                    )
                )
            },
            customPermissionChecker = { true }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = engine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }

        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")

        assertEquals(1, recordedEvents.size)
        assertEquals(sessionOwner, trackerWithEngine.getActiveSessionOwner("0")?.packageName)

        // Closure
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertEquals(2, recordedEvents.size)
        val closure = recordedEvents[1]
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, closure.rawEventType)
        assertEquals(sessionOwner, closure.inferredPackageName)
        assertEquals(AccessClassification.EXPECTED, closure.classification)
        assertNull("Active session owner must be null after closure", trackerWithEngine.getActiveSessionOwner("0"))
    }

    /**
     * Requirement 10: Restart / startup baseline behavior
     */
    @Test
    fun testRestart_startupBaselineBehavior() {
        // Initial callback establishes baseline (0 events)
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, tracker.getCachedState("0"))

        // Stop tracking (simulates service shutdown)
        tracker.stopTracking()
        assertNull("Stopping tracking must clear cached state", tracker.getCachedState("0"))

        // Re-starting tracking: first callback must establish baseline without creating event
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, tracker.getCachedState("0"))

        // Subsequent genuine transition creates an event
        tracker.availabilityCallback.onCameraUnavailable("0")
        assertEquals(1, recordedEvents.size)
    }

    /**
     * Requirement 11: Duplicate suppression across rapid state transitions
     */
    @Test
    fun testDuplicateSuppression_rapidStateTransitions() {
        tracker.availabilityCallback.onCameraAvailable("0") // baseline

        // Rapid duplicate unavailable callbacks
        tracker.availabilityCallback.onCameraUnavailable("0")
        tracker.availabilityCallback.onCameraUnavailable("0")
        tracker.availabilityCallback.onCameraUnavailable("0")

        assertEquals(1, recordedEvents.size)

        // Rapid duplicate available callbacks
        tracker.availabilityCallback.onCameraAvailable("0")
        tracker.availabilityCallback.onCameraAvailable("0")
        tracker.availabilityCallback.onCameraAvailable("0")

        assertEquals(2, recordedEvents.size)
    }

    /**
     * Requirement 12: State reset after session closure
     */
    @Test
    fun testStateReset_afterSessionClosure() {
        val testApp = "org.cameraguard.adversarytest"
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ ->
                listOf(
                    UsageEventRecord(
                        packageName = testApp,
                        timestamp = baseTimestamp - 200L,
                        eventType = UsageEvents.Event.ACTIVITY_RESUMED
                    )
                )
            },
            customPermissionChecker = { true }
        )
        val trackerWithEngine = CameraAvailabilityTracker(inferenceEngine = engine)
        trackerWithEngine.onEventDetected = { recordedEvents.add(it) }

        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertNotNull(trackerWithEngine.getActiveSessionOwner("0"))

        // Close session
        trackerWithEngine.availabilityCallback.onCameraAvailable("0")
        assertNull("Session owner must be reset to null after closure", trackerWithEngine.getActiveSessionOwner("0"))
        assertEquals(RawCameraEventType.CAMERA_BECAME_AVAILABLE, trackerWithEngine.getCachedState("0"))

        // Re-opening starts a fresh session
        trackerWithEngine.availabilityCallback.onCameraUnavailable("0")
        assertEquals(3, recordedEvents.size)
        assertNotNull(trackerWithEngine.getActiveSessionOwner("0"))
    }
}
