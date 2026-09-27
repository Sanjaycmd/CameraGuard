package org.cameraguard.monitoring.telemetry

import android.app.usage.UsageEvents
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualInferenceEngineTest {

    private val baseCameraTime = 100_000L

    /**
     * Requirement: recent ACTIVITY_RESUMED → package correctly inferred
     */
    @Test
    fun testRecentActivityResumed_packageCorrectlyInferred() {
        val events = listOf(
            UsageEventRecord(
                packageName = "com.recent.app",
                timestamp = baseCameraTime - 250L, // 250 ms before camera transition
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { true }
        )

        val result = engine.inferForegroundPackage(baseCameraTime)

        assertEquals("com.recent.app", result.packageName)
        assertEquals(InferenceConfidence.HIGH, result.confidence)
        assertEquals(InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED, result.method)
        assertEquals(true, result.hasCameraPermission)
        assertEquals(250L, result.deltaFromEventMs)
    }

    /**
     * Requirement: ACTIVITY_RESUMED 10–30 seconds earlier → package inferred
     */
    @Test
    fun testActivityResumed10To30SecondsEarlier_packageInferred() {
        val events = listOf(
            UsageEventRecord(
                packageName = "com.whatsapp",
                timestamp = baseCameraTime - 15_000L, // 15 seconds earlier
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { null }
        )

        val result = engine.inferForegroundPackage(baseCameraTime)

        assertEquals("com.whatsapp", result.packageName)
        assertEquals(InferenceConfidence.LOW, result.confidence)
        assertEquals(InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED, result.method)
        assertNull(result.hasCameraPermission)
        assertEquals(15_000L, result.deltaFromEventMs)
    }

    /**
     * Requirement: no ACTIVITY_RESUMED in the 30-second lookback → package remains null / NONE
     */
    @Test
    fun testNoActivityResumedIn30SecondLookback_returnsNullAndNone() {
        // Event is 35 seconds earlier (outside 30-second window [70_000, 100_000])
        val events = listOf(
            UsageEventRecord(
                packageName = "com.stale.app",
                timestamp = baseCameraTime - 35_000L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events }
        )

        val result = engine.inferForegroundPackage(baseCameraTime)

        assertNull(result.packageName)
        assertEquals(InferenceConfidence.NONE, result.confidence)
        assertEquals(InferenceMethod.NONE, result.method)
        assertNull(result.hasCameraPermission)
        assertNull(result.deltaFromEventMs)
    }

    /**
     * Requirement: non-RESUMED events in the 30-second lookback → package remains null / NONE
     */
    @Test
    fun testNonResumedEventsIgnored_returnsNullAndNone() {
        val events = listOf(
            UsageEventRecord(
                packageName = "com.background.app",
                timestamp = baseCameraTime - 5_000L,
                eventType = 2 // e.g. ACTIVITY_PAUSED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events }
        )

        val result = engine.inferForegroundPackage(baseCameraTime)

        assertNull(result.packageName)
        assertEquals(InferenceConfidence.NONE, result.confidence)
        assertEquals(InferenceMethod.NONE, result.method)
    }

    /**
     * Requirement: multiple resumed events → newest event before camera transition is selected
     */
    @Test
    fun testMultipleResumedEvents_newestBeforeTransitionSelected() {
        val events = listOf(
            UsageEventRecord("com.first.app", baseCameraTime - 25_000L, UsageEvents.Event.ACTIVITY_RESUMED),
            UsageEventRecord("com.newest.app", baseCameraTime - 1_200L, UsageEvents.Event.ACTIVITY_RESUMED),
            UsageEventRecord("com.middle.app", baseCameraTime - 10_000L, UsageEvents.Event.ACTIVITY_RESUMED)
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { false }
        )

        val result = engine.inferForegroundPackage(baseCameraTime)

        assertEquals("com.newest.app", result.packageName)
        assertEquals(InferenceConfidence.MEDIUM, result.confidence) // 1200ms <= 2000ms
        assertEquals(1_200L, result.deltaFromEventMs)
        assertEquals(false, result.hasCameraPermission)
    }

    /**
     * Requirement: future ACTIVITY_RESUMED event must not be selected
     */
    @Test
    fun testFutureActivityResumed_notSelected() {
        val events = listOf(
            UsageEventRecord("com.valid.app", baseCameraTime - 3_000L, UsageEvents.Event.ACTIVITY_RESUMED),
            UsageEventRecord("com.future.app", baseCameraTime + 500L, UsageEvents.Event.ACTIVITY_RESUMED)
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events }
        )

        val result = engine.inferForegroundPackage(baseCameraTime)

        assertEquals("com.valid.app", result.packageName)
        assertEquals(3_000L, result.deltaFromEventMs)
        assertEquals(InferenceConfidence.LOW, result.confidence)
    }

    /**
     * Requirement: only future event exists → package remains null / NONE
     */
    @Test
    fun testOnlyFutureEvent_returnsNullAndNone() {
        val events = listOf(
            UsageEventRecord("com.future.app", baseCameraTime + 1_000L, UsageEvents.Event.ACTIVITY_RESUMED)
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events }
        )

        val result = engine.inferForegroundPackage(baseCameraTime)

        assertNull(result.packageName)
        assertEquals(InferenceConfidence.NONE, result.confidence)
    }

    /**
     * Requirement: findForegroundCameraActivity identifies com.android.camera from ACTIVITY_RESUMED
     */
    @Test
    fun testFindForegroundCameraActivity_identifiesStockCameraActivityResumed() {
        val events = listOf(
            UsageEventRecord(
                packageName = "com.android.camera",
                timestamp = baseCameraTime - 1_000L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                className = "com.android.camera.CameraActivity"
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { true }
        )

        val candidate = engine.findForegroundCameraActivity(baseCameraTime)

        assertNotNull(candidate)
        assertEquals("com.android.camera", candidate?.packageName)
        assertEquals("com.android.camera.CameraActivity", candidate?.className)
        assertEquals(baseCameraTime - 1_000L, candidate?.timestamp)
        assertEquals(true, candidate?.hasCameraPermission)
    }

    /**
     * Requirement: findForegroundCameraActivity ignores non-resumed events (PAUSED, STOPPED)
     */
    @Test
    fun testFindForegroundCameraActivity_ignoresPausedAndStoppedEvents() {
        val events = listOf(
            UsageEventRecord(
                packageName = "com.android.camera",
                timestamp = baseCameraTime - 500L,
                eventType = 2, // ACTIVITY_PAUSED
                className = "com.android.camera.CameraActivity"
            ),
            UsageEventRecord(
                packageName = "com.android.camera",
                timestamp = baseCameraTime - 300L,
                eventType = 23, // ACTIVITY_STOPPED
                className = "com.android.camera.CameraActivity"
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { true }
        )

        val candidate = engine.findForegroundCameraActivity(baseCameraTime)
        assertNull(candidate)
    }

    /**
     * Requirement: findForegroundCameraActivity ignores future events (timestamp > currentTime)
     */
    @Test
    fun testFindForegroundCameraActivity_ignoresFutureEvents() {
        val events = listOf(
            UsageEventRecord(
                packageName = "com.android.camera",
                timestamp = baseCameraTime + 100L, // in future
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                className = "com.android.camera.CameraActivity"
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { true }
        )

        val candidate = engine.findForegroundCameraActivity(baseCameraTime)
        assertNull(candidate)
    }

    /**
     * Requirement: findForegroundCameraActivity picks the most recent ACTIVITY_RESUMED within the 30s window
     */
    @Test
    fun testFindForegroundCameraActivity_picksMostRecentWithinWindow() {
        val events = listOf(
            UsageEventRecord(
                packageName = "com.android.camera",
                timestamp = baseCameraTime - 20_000L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                className = "com.android.camera.CameraActivity"
            ),
            UsageEventRecord(
                packageName = "com.android.camera",
                timestamp = baseCameraTime - 2_000L, // more recent
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                className = "com.android.camera.CameraActivity"
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { true }
        )

        val candidate = engine.findForegroundCameraActivity(baseCameraTime)
        assertNotNull(candidate)
        assertEquals(baseCameraTime - 2_000L, candidate?.timestamp)
    }

    /**
     * Requirement: findForegroundCameraActivity preserves permission nullability (UNVERIFIED)
     */
    @Test
    fun testFindForegroundCameraActivity_preservesPermissionNullability() {
        val events = listOf(
            UsageEventRecord(
                packageName = "com.android.camera",
                timestamp = baseCameraTime - 500L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                className = "com.android.camera.CameraActivity"
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { null } // UNVERIFIED / null
        )

        val candidate = engine.findForegroundCameraActivity(baseCameraTime)
        assertNotNull(candidate)
        assertNull(candidate?.hasCameraPermission)
    }

    /**
     * Requirement: findForegroundCameraActivity ignores non-camera application (e.g. WhatsApp, Browser)
     */
    @Test
    fun testFindForegroundCameraActivity_ignoresNonCameraApps() {
        val events = listOf(
            UsageEventRecord(
                packageName = "com.whatsapp",
                timestamp = baseCameraTime - 500L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                className = "com.whatsapp.HomeActivity"
            ),
            UsageEventRecord(
                packageName = "com.android.chrome",
                timestamp = baseCameraTime - 200L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                className = "org.chromium.chrome.browser.ChromeTabbedActivity"
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { true }
        )

        val candidate = engine.findForegroundCameraActivity(baseCameraTime)
        assertNull("Non-camera apps must not be selected as UsageStats fallback candidates", candidate)
    }

    /**
     * Requirement: isCameraApplication recognition logic
     */
    @Test
    fun testIsCameraApplication_recognizesCameraPackagesAndActivities() {
        val engine = ContextualInferenceEngine()

        assertTrue(engine.isCameraApplication("com.android.camera"))
        assertTrue(engine.isCameraApplication("com.google.android.GoogleCamera"))
        assertTrue(engine.isCameraApplication("com.sec.android.app.camera"))
        assertTrue(engine.isCameraApplication("org.codeaurora.snapcam"))
        assertTrue(engine.isCameraApplication("com.vendor.camera"))
        assertTrue(engine.isCameraApplication("com.vendor.camera.app"))
        assertTrue(engine.isCameraApplication("com.custom.app", "com.custom.app.CameraActivity"))
        assertTrue(engine.isCameraApplication("com.custom.app", "com.custom.app.Camera"))

        assertFalse(engine.isCameraApplication("com.whatsapp", "com.whatsapp.HomeActivity"))
        assertFalse(engine.isCameraApplication("com.instagram.android", "com.instagram.mainactivity.MainActivity"))
        assertFalse(engine.isCameraApplication("org.telegram.messenger", "org.telegram.ui.LaunchActivity"))
    }

    /**
     * Requirement 1 (Part 8): foreground candidate attribution
     */
    @Test
    fun testForegroundCandidateAttribution() {
        val events = listOf(
            UsageEventRecord(
                packageName = "com.google.android.GoogleCamera",
                timestamp = baseCameraTime - 200L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { true }
        )

        val result = engine.inferForegroundPackage(baseCameraTime)

        assertEquals("com.google.android.GoogleCamera", result.packageName)
        assertEquals(InferenceConfidence.HIGH, result.confidence)
        assertEquals(InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED, result.method)
        assertEquals(true, result.hasCameraPermission)
        assertEquals(200L, result.deltaFromEventMs)
    }

    /**
     * Requirement 2 (Part 8): background camera caller with prior transitioning camera app
     */
    @Test
    fun testBackgroundCameraCaller_attributesTransitioningCameraCapableApp() {
        val events = listOf(
            UsageEventRecord(
                packageName = "org.cameraguard.adversarytest",
                timestamp = baseCameraTime - 1_500L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            ),
            UsageEventRecord(
                packageName = "com.android.calculator2",
                timestamp = baseCameraTime - 200L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { pkg -> pkg == "org.cameraguard.adversarytest" }
        )

        val result = engine.inferForegroundPackage(baseCameraTime)

        // When foreground app (calculator) has no camera permission, the transitioning camera-capable app is attributed
        assertEquals("org.cameraguard.adversarytest", result.packageName)
        assertEquals(InferenceConfidence.MEDIUM, result.confidence)
        assertEquals(1_500L, result.deltaFromEventMs)
        assertEquals(true, result.hasCameraPermission)
    }

    /**
     * Requirement 3 (Part 8): CameraGuard foreground while another app owns camera
     */
    @Test
    fun testCameraGuardForeground_neverSelfAttributed() {
        val events = listOf(
            UsageEventRecord(
                packageName = "org.cameraguard.adversarytest",
                timestamp = baseCameraTime - 1_200L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            ),
            UsageEventRecord(
                packageName = "org.cameraguard",
                timestamp = baseCameraTime - 100L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { pkg -> pkg == "org.cameraguard.adversarytest" }
        )

        val result = engine.inferForegroundPackage(baseCameraTime)

        // Must attribute the actual caller, never self-attribute to org.cameraguard
        assertEquals("org.cameraguard.adversarytest", result.packageName)
        assertEquals(InferenceConfidence.MEDIUM, result.confidence)
        assertEquals(1_200L, result.deltaFromEventMs)
    }

    /**
     * Requirement 4 (Part 8): camera permission denial foreground candidate
     */
    @Test
    fun testCameraPermissionDenial_candidateLacksPermission() {
        val events = listOf(
            UsageEventRecord(
                packageName = "com.unprivileged.app",
                timestamp = baseCameraTime - 300L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { false }
        )

        val result = engine.inferForegroundPackage(baseCameraTime)

        assertEquals("com.unprivileged.app", result.packageName)
        assertEquals(InferenceConfidence.HIGH, result.confidence)
        assertEquals(false, result.hasCameraPermission)
    }

    /**
     * Requirement 5 (Part 8): transient/probe transition attribution
     */
    @Test
    fun testTransientProbeTransition_unprivilegedAppAttributedForPolicyEvaluation() {
        val events = listOf(
            UsageEventRecord(
                packageName = "org.cameraguard.adversarytest",
                timestamp = baseCameraTime - 50L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { true }
        )

        val result = engine.inferForegroundPackage(baseCameraTime)

        assertEquals("org.cameraguard.adversarytest", result.packageName)
        assertEquals(InferenceConfidence.HIGH, result.confidence)
        assertEquals(50L, result.deltaFromEventMs)
    }

    /**
     * Requirement 6 (Part 8): unknown attribution when no camera-capable app is present
     */
    @Test
    fun testUnknownAttribution_whenOnlyHostMonitorInEvents() {
        val events = listOf(
            UsageEventRecord(
                packageName = "org.cameraguard",
                timestamp = baseCameraTime - 150L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { false }
        )

        val result = engine.inferForegroundPackage(baseCameraTime)

        assertNull(result.packageName)
        assertEquals(InferenceConfidence.NONE, result.confidence)
        assertEquals(InferenceMethod.NONE, result.method)
        assertNull(result.hasCameraPermission)
        assertNull(result.deltaFromEventMs)
        assertEquals(1, result.recentActivityCount30s)
    }

    /**
     * Requirement 7 (Part 8): lifecycle closure without caller attribution
     */
    @Test
    fun testLifecycleClosure_unattributedSessionRemainsNullOnClosure() {
        val tracker = org.cameraguard.monitoring.CameraAvailabilityTracker()
        tracker.availabilityCallback.onCameraAvailable("0") // baseline
        tracker.availabilityCallback.onCameraUnavailable("0") // un-attributed opening (no UsageStats provider)
        val openingOwner = tracker.getActiveSessionOwner("0")
        assertNull("Opening without candidate should not store session owner", openingOwner)

        var closureEvent: org.cameraguard.data.model.CameraAccessEvent? = null
        tracker.onEventDetected = { event -> closureEvent = event }

        tracker.availabilityCallback.onCameraAvailable("0") // closure
        assertNotNull(closureEvent)
        assertNull("Closure without opening owner must have null package name", closureEvent?.inferredPackageName)
        assertEquals(InferenceConfidence.NONE, closureEvent?.packageInferenceConfidence)
    }

    /**
     * Requirement 8: Adaptive Transition Corroboration detects camera app resumed within lookahead window
     */
    @Test
    fun testCorroborateTransition_detectsCameraResumedInLookaheadWindow() {
        val cameraPackage = "com.android.camera"
        val events = listOf(
            UsageEventRecord(
                packageName = "com.android.launcher3",
                timestamp = baseCameraTime - 500L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            ),
            UsageEventRecord(
                packageName = cameraPackage,
                timestamp = baseCameraTime + 200L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { it == cameraPackage }
        )

        val corroborated = engine.corroborateTransition(baseCameraTime, 500L)
        assertNotNull("Should corroborate camera app resumed in window", corroborated)
        assertEquals(cameraPackage, corroborated?.packageName)
        assertEquals(InferenceConfidence.HIGH, corroborated?.confidence)
        assertEquals(InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED, corroborated?.method)
        assertEquals(true, corroborated?.hasCameraPermission)
        assertEquals(200L, corroborated?.deltaFromEventMs)
    }

    @Test
    fun testCorroborateTransition_returnsNullWhenNoCameraAppInWindow() {
        val nonCameraApp = "com.example.calculator"
        val events = listOf(
            UsageEventRecord(
                packageName = "com.android.launcher3",
                timestamp = baseCameraTime - 500L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            ),
            UsageEventRecord(
                packageName = nonCameraApp,
                timestamp = baseCameraTime + 200L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { false }
        )

        val corroborated = engine.corroborateTransition(baseCameraTime, 500L)
        assertNull("Should return null when no camera-capable app resumed in window", corroborated)
    }

    @Test
    fun testCorroborateTransition_ignoresResumptionAfterWindow() {
        val cameraPackage = "com.android.camera"
        val events = listOf(
            UsageEventRecord(
                packageName = cameraPackage,
                timestamp = baseCameraTime + 600L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { it == cameraPackage }
        )

        val corroborated = engine.corroborateTransition(baseCameraTime, 500L)
        assertNull("Should return null when camera app resumes after lookahead window", corroborated)
    }
}
