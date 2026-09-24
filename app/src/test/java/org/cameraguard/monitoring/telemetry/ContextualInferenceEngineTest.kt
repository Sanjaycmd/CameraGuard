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
}
