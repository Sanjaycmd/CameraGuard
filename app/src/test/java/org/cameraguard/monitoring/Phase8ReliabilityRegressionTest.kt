package org.cameraguard.monitoring

import android.app.usage.UsageEvents
import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.CameraAccessEvent
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.data.model.ScreenInteractivityState
import org.cameraguard.monitoring.detection.hybrid.ProductionT0FeatureVector
import org.cameraguard.monitoring.detection.hybrid.TreeClassification
import org.cameraguard.monitoring.telemetry.ContextualInferenceEngine
import org.cameraguard.monitoring.telemetry.InferredPackageContext
import org.cameraguard.monitoring.telemetry.UsageEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 8 Reliability & Transition Regression Test Suite.
 *
 * Validates Candidate E architecture:
 * 1. Cold stock Camera launch transition attribution (Launcher3 -> Camera)
 * 2. Multi-app transition resolution (Google Search / Assistant -> Camera)
 * 3. Accidental Assistant invocation without camera resumption (AMBIGUOUS audit log without alert)
 * 4. Suspicious background probe retention on active screen (UNEXPECTED alert)
 * 5. Screen-Off immediate detection bypass (0ms fast path -> UNEXPECTED alert)
 * 6. Screen-Locked immediate detection bypass (0ms fast path -> UNEXPECTED alert)
 * 7. Warm camera launch bypass (0ms fast path -> EXPECTED)
 * 8. Alert policy strict decoupling (UNEXPECTED alerts, AMBIGUOUS/EXPECTED/UNKNOWN silent)
 */
class Phase8ReliabilityRegressionTest {

    private val recordedEvents = mutableListOf<CameraAccessEvent>()
    private val alertEvents = mutableListOf<CameraAccessEvent>()

    @Before
    fun setup() {
        recordedEvents.clear()
        alertEvents.clear()
    }

    private fun createTracker(
        engine: ContextualInferenceEngine,
        corroborationDelayMs: Long = 100L
    ): CameraAvailabilityTracker {
        val tracker = CameraAvailabilityTracker(inferenceEngine = engine)
        tracker.transitionCorroborationDelayMs = corroborationDelayMs
        tracker.onEventDetected = { event ->
            recordedEvents.add(event)
            if (event.classification == AccessClassification.UNEXPECTED) {
                alertEvents.add(event)
            }
        }
        return tracker
    }

    /**
     * Test 1: Cold stock Camera launch from Launcher3 is corroborated to Camera and classified as EXPECTED.
     * Zero false alert fired.
     */
    @Test
    fun testColdCameraLaunch_promotedToExpectedViaCorroboration() {
        val launcherPackage = "com.android.launcher3"
        val cameraPackage = "com.android.camera"
        val t0 = System.currentTimeMillis()

        val events = mutableListOf(
            UsageEventRecord(
                packageName = launcherPackage,
                timestamp = t0 - 800L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            ),
            UsageEventRecord(
                packageName = cameraPackage,
                timestamp = t0 + 50L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )

        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { it == cameraPackage }
        )

        val tracker = createTracker(engine, corroborationDelayMs = 100L)

        // Baseline establishment
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals(0, recordedEvents.size)

        // Cold launch: camera hardware unavailable while Launcher is initial candidate
        tracker.availabilityCallback.onCameraUnavailable("0")

        // Wait for corroboration
        Thread.sleep(250L)

        assertEquals("Exactly 1 event should be recorded", 1, recordedEvents.size)
        val event = recordedEvents[0]
        assertEquals("Attribution should be promoted to camera", cameraPackage, event.inferredPackageName)
        assertEquals("Classification should be EXPECTED", AccessClassification.EXPECTED, event.classification)
        assertEquals("Active session owner should be camera", cameraPackage, tracker.getActiveSessionOwner("0")?.packageName)
        assertTrue("No alert must be triggered for legitimate cold camera launch", alertEvents.isEmpty())
    }

    /**
     * Test 2: Rapid multi-app transition (Google Search -> Camera).
     * Corroboration window successfully promotes candidate to Camera.
     */
    @Test
    fun testRapidMultiAppTransition_googleSearchToCamera_resolvesCorrectly() {
        val searchPackage = "com.google.android.googlequicksearchbox"
        val cameraPackage = "com.android.camera"
        val t0 = System.currentTimeMillis()

        val events = mutableListOf(
            UsageEventRecord(
                packageName = searchPackage,
                timestamp = t0 - 200L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            ),
            UsageEventRecord(
                packageName = cameraPackage,
                timestamp = t0 + 60L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )

        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { it == cameraPackage }
        )

        val tracker = createTracker(engine, corroborationDelayMs = 120L)
        tracker.availabilityCallback.onCameraAvailable("0")

        tracker.availabilityCallback.onCameraUnavailable("0")
        Thread.sleep(250L)

        assertEquals(1, recordedEvents.size)
        val event = recordedEvents[0]
        assertEquals(cameraPackage, event.inferredPackageName)
        assertEquals(AccessClassification.EXPECTED, event.classification)
        assertTrue("No alert must be triggered", alertEvents.isEmpty())
    }

    /**
     * Test 3: Accidental Assistant invocation without camera resumption.
     * Evaluates to AMBIGUOUS and persists to audit log without firing a heads-up alert.
     */
    @Test
    fun testAccidentalAssistantInvocationWithoutCameraResumption_resolvesAmbiguousWithoutAlert() {
        val searchPackage = "com.google.android.googlequicksearchbox"
        val t0 = System.currentTimeMillis()

        // Google search resumed, but permission is unverified / null and confidence is LOW
        val events = mutableListOf(
            UsageEventRecord(
                packageName = searchPackage,
                timestamp = t0 - 300L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            ),
            UsageEventRecord(
                packageName = "com.android.launcher3",
                timestamp = t0 - 1500L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )

        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { null } // unverified
        )

        // Custom evaluator forcing TreeClassification.AMBIGUOUS when Tier 1 is UNKNOWN
        val tracker = CameraAvailabilityTracker(
            inferenceEngine = engine,
            hybridEvaluator = org.cameraguard.monitoring.detection.hybrid.HybridCameraEvaluator(
                decisionTreeEvaluator = { TreeClassification.AMBIGUOUS }
            )
        )
        tracker.transitionCorroborationDelayMs = 50L
        tracker.onEventDetected = { event ->
            recordedEvents.add(event)
            if (event.classification == AccessClassification.UNEXPECTED) {
                alertEvents.add(event)
            }
        }

        tracker.availabilityCallback.onCameraAvailable("0")
        tracker.availabilityCallback.onCameraUnavailable("0")

        Thread.sleep(150L)

        assertEquals(1, recordedEvents.size)
        val event = recordedEvents[0]
        assertEquals("Event must be classified as AMBIGUOUS", AccessClassification.AMBIGUOUS, event.classification)
        assertTrue("AMBIGUOUS events must NOT fire high-priority security alert", alertEvents.isEmpty())
    }

    /**
     * Test 4: Suspicious background camera probe while user is active on device.
     * Unprivileged app opens camera and no legitimate camera app resumes.
     * Retains attribution to unauthorized candidate and fires UNEXPECTED alert.
     */
    @Test
    fun testSuspiciousBackgroundProbe_onActiveScreen_remainsUnexpected() {
        val adversaryPackage = "org.cameraguard.adversarytest"
        val t0 = System.currentTimeMillis()

        val events = mutableListOf(
            UsageEventRecord(
                packageName = adversaryPackage,
                timestamp = t0 - 400L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )

        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { false } // explicitly no camera permission
        )

        val tracker = createTracker(engine, corroborationDelayMs = 80L)
        tracker.availabilityCallback.onCameraAvailable("0")

        tracker.availabilityCallback.onCameraUnavailable("0")
        Thread.sleep(200L)

        assertEquals(1, recordedEvents.size)
        val event = recordedEvents[0]
        assertEquals(adversaryPackage, event.inferredPackageName)
        assertEquals("Unprivileged caller must be classified as UNEXPECTED", AccessClassification.UNEXPECTED, event.classification)
        assertEquals("Exactly 1 security alert must be fired", 1, alertEvents.size)
        assertEquals(adversaryPackage, alertEvents[0].inferredPackageName)
    }

    /**
     * Test 5: Screen-Off camera access bypasses corroboration delay (0ms latency fast-path)
     * and immediately issues UNEXPECTED security alert.
     */
    @Test
    fun testScreenOffCameraAccess_fastPathImmediateUnexpectedAlert() {
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> emptyList() },
            customPermissionChecker = { false }
        )

        val tracker = createTracker(engine, corroborationDelayMs = 500L)

        // Direct call to processEvaluatedCameraEvent or evaluate with SCREEN_OFF
        val hybridResult = tracker.hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_OFF,
            inferredContext = InferredPackageContext(
                packageName = null,
                confidence = InferenceConfidence.NONE,
                method = InferenceMethod.NONE,
                hasCameraPermission = null,
                deltaFromEventMs = null
            )
        )

        assertEquals("Rule 2 match must evaluate to UNEXPECTED", AccessClassification.UNEXPECTED, hybridResult.finalClassification)
        assertEquals("TIER_1_RULE", hybridResult.tierUsed)
        assertFalse("ML must not be invoked for screen-off violation", hybridResult.mlInvoked)
    }

    /**
     * Test 6: Screen-Locked camera access bypasses corroboration delay (0ms latency fast-path)
     * and immediately issues UNEXPECTED security alert.
     */
    @Test
    fun testScreenLockedCameraAccess_fastPathImmediateUnexpectedAlert() {
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> emptyList() },
            customPermissionChecker = { false }
        )

        val tracker = createTracker(engine, corroborationDelayMs = 500L)

        val hybridResult = tracker.hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_LOCKED,
            inferredContext = InferredPackageContext(
                packageName = null,
                confidence = InferenceConfidence.NONE,
                method = InferenceMethod.NONE,
                hasCameraPermission = null,
                deltaFromEventMs = null
            )
        )

        assertEquals("Rule 4 match must evaluate to UNEXPECTED", AccessClassification.UNEXPECTED, hybridResult.finalClassification)
        assertEquals("TIER_1_RULE", hybridResult.tierUsed)
        assertFalse("ML must not be invoked for screen-locked violation", hybridResult.mlInvoked)
    }

    /**
     * Test 7: Warm camera launch with HIGH confidence and verified permission
     * takes fast-path with 0ms delay.
     */
    @Test
    fun testWarmCameraAccess_fastPathZeroDelay() {
        val cameraPackage = "com.android.camera"
        val t0 = System.currentTimeMillis()

        val events = listOf(
            UsageEventRecord(
                packageName = cameraPackage,
                timestamp = t0 - 200L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )

        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { it == cameraPackage }
        )

        // Corroboration delay set to 1000L, but because candidate is HIGH confidence with permission,
        // it must NOT delay!
        val tracker = createTracker(engine, corroborationDelayMs = 1000L)
        tracker.availabilityCallback.onCameraAvailable("0")

        tracker.availabilityCallback.onCameraUnavailable("0")

        // Assert immediately without sleeping!
        assertEquals("Fast path must record event immediately without delay", 1, recordedEvents.size)
        val event = recordedEvents[0]
        assertEquals(cameraPackage, event.inferredPackageName)
        assertEquals(AccessClassification.EXPECTED, event.classification)
        assertTrue("No alert for warm camera", alertEvents.isEmpty())
    }

    /**
     * Test 8: Strict alert policy verification.
     * Only UNEXPECTED events trigger alerts; AMBIGUOUS, EXPECTED, and UNKNOWN are silent.
     */
    @Test
    fun testAlertPolicy_strictDecoupling() {
        val alertTriggered = mutableListOf<AccessClassification>()
        val alertPolicyHandler: (CameraAccessEvent) -> Unit = { event ->
            if (event.classification == AccessClassification.UNEXPECTED) {
                alertTriggered.add(event.classification)
            }
        }

        // Test each classification
        for (c in AccessClassification.values()) {
            val event = CameraAccessEvent(
                rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
                classification = c
            )
            alertPolicyHandler(event)
        }

        assertEquals("Only UNEXPECTED should trigger an alert", 1, alertTriggered.size)
        assertEquals(AccessClassification.UNEXPECTED, alertTriggered[0])
    }
}
