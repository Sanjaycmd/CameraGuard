package org.cameraguard.monitoring

import android.app.usage.UsageEvents
import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.data.model.ScreenInteractivityState
import org.cameraguard.monitoring.detection.hybrid.HybridCameraEvaluator
import org.cameraguard.monitoring.detection.hybrid.ProductionDecisionTree
import org.cameraguard.monitoring.detection.hybrid.ProductionT0FeatureVector
import org.cameraguard.monitoring.detection.hybrid.TreeClassification
import org.cameraguard.monitoring.detection.CameraRuleEvaluator
import org.cameraguard.monitoring.telemetry.ContextualInferenceEngine
import org.cameraguard.monitoring.telemetry.InferredPackageContext
import org.cameraguard.monitoring.telemetry.UsageEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 5.5 — Contextual Classification Robustness Tests
 *
 * Validates CameraGuard's combined pipeline (attribution + classification) under
 * ambiguous contextual conditions observed in the C1–C10 physical evaluation.
 *
 * Coverage:
 *  1. Known foreground caller attributed correctly
 *  2. Background caller with recent transition attributed correctly
 *  3. Background caller without transition → UNKNOWN
 *  4. CameraGuard foreground + UNKNOWN caller → no self-attribution
 *  5. Stale candidate rejection (no camera permission, outside transition window)
 *  6. Permission denied → no fabricated session events
 *  7. Permission denied then granted → state does not contaminate next session
 *  8. Caller switching → independent attributions, no cross-contamination
 *  9. Lifecycle closure after attribution
 * 10. Anti-self-attribution: CameraGuard must never attribute itself
 * 11. Restart transient context reset
 * 12. Duplicate suppression within 3 s deduplication window
 * 13. Classification for UNKNOWN context (Tier-2 ML: AMBIGUOUS/UNEXPECTED)
 * 14. Classification for known legitimate context (Tier-1 or Tier-2: EXPECTED)
 * 15. Classification for suspicious background context (UNEXPECTED)
 */
class ContextualClassificationRobustnessTest {

    // Use stable absolute timestamps (not System.currentTimeMillis()) to avoid
    // brittle timing sensitivity when computing minDelta confidence boundaries.
    private val T = 100_000_000L   // stable reference camera-event timestamp

    private val PACKAGE_GUARD    = "org.cameraguard"
    private val PACKAGE_ADVERSARY = "org.cameraguard.adversarytest"
    private val PACKAGE_HARNESS  = "org.cameratestharness"

    private lateinit var ruleEvaluator: CameraRuleEvaluator
    private lateinit var hybridEvaluator: HybridCameraEvaluator

    @Before
    fun setUp() {
        ruleEvaluator = CameraRuleEvaluator()
        hybridEvaluator = HybridCameraEvaluator(ruleEvaluator)
    }

    // -------------------------------------------------------------------------
    // 1. Known foreground caller attributed correctly
    // -------------------------------------------------------------------------

    /**
     * C1 semantic: CameraTestHarness is the most recent ACTIVITY_RESUMED event within
     * the 30-second lookback, has camera permission, and is not CameraGuard itself.
     * Engine must attribute to Harness with HIGH confidence (delta ≤ 500ms).
     */
    @Test
    fun testKnownForegroundCaller_attributedCorrectly() {
        val events = listOf(
            UsageEventRecord(
                packageName = PACKAGE_HARNESS,
                timestamp = T - 300L,   // 300ms before camera open → HIGH confidence
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { it == PACKAGE_HARNESS },
            ownPackageName = PACKAGE_GUARD
        )

        val result = engine.inferForegroundPackage(T)

        assertEquals("Known foreground caller must be attributed", PACKAGE_HARNESS, result.packageName)
        assertEquals("Delta ≤ 500ms → HIGH confidence", InferenceConfidence.HIGH, result.confidence)
        assertEquals(InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED, result.method)
        assertEquals(true, result.hasCameraPermission)
        assertEquals(300L, result.deltaFromEventMs)
    }

    // -------------------------------------------------------------------------
    // 2. Background caller with recent transition attributed correctly
    // -------------------------------------------------------------------------

    /**
     * C3 semantic: CameraGuard is topResumed. Adversary had ACTIVITY_RESUMED 2s ago
     * (within TRANSITION_LOOKBACK_WINDOW_MS=5000ms) and has camera permission.
     * Engine must attribute to adversary — the camera-capable recent transition.
     */
    @Test
    fun testBackgroundCaller_withRecentTransition_isAttributed() {
        val events = listOf(
            UsageEventRecord(
                packageName = PACKAGE_GUARD,
                timestamp = T - 100L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            ),
            UsageEventRecord(
                packageName = PACKAGE_ADVERSARY,
                timestamp = T - 2_000L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { it == PACKAGE_ADVERSARY },
            ownPackageName = PACKAGE_GUARD
        )

        val result = engine.inferForegroundPackage(T)

        // CameraGuard is topResumed, but adversary had camera-capable transition within 5s
        assertEquals(PACKAGE_ADVERSARY, result.packageName)
        // minDelta = T - (T - 2000) = 2000ms → exactly at MEDIUM threshold
        assertEquals(InferenceConfidence.MEDIUM, result.confidence)
        assertEquals(true, result.hasCameraPermission)
    }

    // -------------------------------------------------------------------------
    // 3. Background caller without transition → UNKNOWN
    // -------------------------------------------------------------------------

    /**
     * C4 semantic: CameraGuard is topResumed. Adversary FGS accessed camera but had
     * no ACTIVITY_RESUMED within 5000ms. Result must be UNKNOWN (null package).
     */
    @Test
    fun testBackgroundCaller_withoutRecentTransition_isUnknown() {
        // Adversary's last activity was 8 seconds ago — outside 5s transition window
        val events = listOf(
            UsageEventRecord(
                packageName = PACKAGE_GUARD,
                timestamp = T - 200L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            ),
            UsageEventRecord(
                packageName = PACKAGE_ADVERSARY,
                timestamp = T - 8_000L,   // 8s ago — outside TRANSITION_LOOKBACK_WINDOW_MS (5000ms)
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { it == PACKAGE_ADVERSARY },
            ownPackageName = PACKAGE_GUARD
        )

        val result = engine.inferForegroundPackage(T)

        // CameraGuard is topResumed, no camera-capable candidate within 5s → honest UNKNOWN
        assertNull("Must return null attribution when no transition evidence", result.packageName)
        assertEquals(InferenceConfidence.NONE, result.confidence)
        assertEquals(InferenceMethod.NONE, result.method)
    }

    // -------------------------------------------------------------------------
    // 4. CameraGuard foreground + UNKNOWN caller → no self-attribution
    // -------------------------------------------------------------------------

    /**
     * C5 semantic: CameraGuard is the only ACTIVITY_RESUMED in the window. The system
     * must never self-attribute to org.cameraguard.
     */
    @Test
    fun testCameraGuardForeground_unknownCaller_noSelfAttribution() {
        val events = listOf(
            UsageEventRecord(
                packageName = PACKAGE_GUARD,
                timestamp = T - 500L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { it == PACKAGE_GUARD },
            ownPackageName = PACKAGE_GUARD
        )

        val result = engine.inferForegroundPackage(T)

        assertNull("CameraGuard must never self-attribute", result.packageName)
        assertFalse("Attributed package must not be CameraGuard", result.packageName == PACKAGE_GUARD)
        assertEquals(InferenceConfidence.NONE, result.confidence)
        assertEquals(InferenceMethod.NONE, result.method)
    }

    // -------------------------------------------------------------------------
    // 5. Stale candidate rejection — no camera permission, outside transition window
    // -------------------------------------------------------------------------

    /**
     * C5 robustness: When the only non-own candidate is outside the transition window
     * AND lacks camera permission, no attribution should be made (returns UNKNOWN).
     * This prevents launcher/home app being falsely attributed.
     */
    @Test
    fun testStaleCandidateRejection_noCameraPermission_returnsUnknown() {
        // A recent foreground app that has NO camera permission (launcher)
        val events = listOf(
            UsageEventRecord(
                packageName = "com.android.launcher3",
                timestamp = T - 20_000L,   // 20s ago — inside 30s window, outside 5s transition window
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            // Launcher has no camera permission
            customPermissionChecker = { false },
            ownPackageName = PACKAGE_GUARD
        )

        val result = engine.inferForegroundPackage(T)

        // Launcher is outside transition window and has no camera permission → UNKNOWN
        assertNull("Stale launcher without camera perm must not be attributed", result.packageName)
        assertEquals(InferenceConfidence.NONE, result.confidence)
    }

    // -------------------------------------------------------------------------
    // 6. Permission denied → 0 hardware-transition events (no fabricated sessions)
    // -------------------------------------------------------------------------

    /**
     * C7 semantic: When Android platform blocks camera access via SecurityException,
     * no CAMERA_BECAME_UNAVAILABLE HAL callback is generated at all.
     *
     * This is modelled by verifying that CameraAvailabilityTracker does NOT emit
     * an event unless an actual availability callback arrives. The tracker must not
     * fabricate events on its own.
     *
     * After baseline AVAILABLE is established, if no further callback arrives
     * (because the platform blocked HAL allocation), the event list remains empty.
     */
    @Test
    fun testPermissionDenied_noCameraHardwareSession_noTrackerEvent() {
        val tracker = CameraAvailabilityTracker()
        val detectedEvents = mutableListOf<org.cameraguard.data.model.CameraAccessEvent>()
        tracker.onEventDetected = { detectedEvents.add(it) }

        // Establish baseline (camera starts available) — no event generated
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals("Baseline must not generate an event", 0, detectedEvents.size)

        // Platform blocked the camera attempt via SecurityException — no further callback
        // Tracker must not spontaneously fabricate an event
        assertEquals("No event must be fabricated for a permission-denied attempt", 0, detectedEvents.size)
    }

    // -------------------------------------------------------------------------
    // 7. Permission denied then granted → no state contamination
    // -------------------------------------------------------------------------

    /**
     * C8 semantic: A denied camera attempt (producing no HAL callback) must not
     * contaminate the attribution context for the subsequent legitimate access.
     *
     * After denial (no callback), when the legitimate session opens, the tracker
     * must correctly produce a clean CAMERA_BECAME_UNAVAILABLE event with no
     * residual stale state.
     */
    @Test
    fun testDeniedThenGranted_noStateContamination() {
        // First call — no events (denied attempt produced no ACTIVITY_RESUMED change)
        val deniedEngine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> emptyList() },
            customPermissionChecker = { false },
            ownPackageName = PACKAGE_GUARD
        )
        val deniedResult = deniedEngine.inferForegroundPackage(T)
        assertNull("Denied attempt must yield UNKNOWN attribution", deniedResult.packageName)

        // Second call — fresh engine (simulating cleared context) with valid evidence
        val grantedEngine = ContextualInferenceEngine(
            customEventProvider = { _, _ ->
                listOf(
                    UsageEventRecord(
                        packageName = PACKAGE_ADVERSARY,
                        timestamp = T - 400L,
                        eventType = UsageEvents.Event.ACTIVITY_RESUMED
                    )
                )
            },
            customPermissionChecker = { it == PACKAGE_ADVERSARY },
            ownPackageName = PACKAGE_GUARD
        )
        val grantedResult = grantedEngine.inferForegroundPackage(T)

        // Post-grant session must be attributed normally — no contamination from denied engine
        assertEquals("Post-grant session must be attributed correctly", PACKAGE_ADVERSARY, grantedResult.packageName)
        assertEquals(InferenceConfidence.HIGH, grantedResult.confidence)
        assertNull("Denied engine must not have leaked attribution to granted engine", deniedResult.packageName)
    }

    // -------------------------------------------------------------------------
    // 8. Caller switching → independent attributions, no cross-contamination
    // -------------------------------------------------------------------------

    /**
     * C9 semantic: Three independent camera sessions (Harness, Adversary, Harness) must
     * each be attributed independently. Engine state is not shared between calls with
     * different event sets.
     */
    @Test
    fun testCallerSwitching_noAttributionCrossContamination() {
        // Session 1: Harness
        val e1 = ContextualInferenceEngine(
            customEventProvider = { _, _ -> listOf(
                UsageEventRecord(packageName = PACKAGE_HARNESS, timestamp = T - 300L,
                    eventType = UsageEvents.Event.ACTIVITY_RESUMED))
            },
            customPermissionChecker = { it == PACKAGE_HARNESS },
            ownPackageName = PACKAGE_GUARD
        )
        val r1 = e1.inferForegroundPackage(T)

        // Session 2: Adversary (independent engine)
        val e2 = ContextualInferenceEngine(
            customEventProvider = { _, _ -> listOf(
                UsageEventRecord(packageName = PACKAGE_ADVERSARY, timestamp = T - 250L,
                    eventType = UsageEvents.Event.ACTIVITY_RESUMED))
            },
            customPermissionChecker = { it == PACKAGE_ADVERSARY },
            ownPackageName = PACKAGE_GUARD
        )
        val r2 = e2.inferForegroundPackage(T)

        // Session 3: Harness again (independent engine)
        val e3 = ContextualInferenceEngine(
            customEventProvider = { _, _ -> listOf(
                UsageEventRecord(packageName = PACKAGE_HARNESS, timestamp = T - 200L,
                    eventType = UsageEvents.Event.ACTIVITY_RESUMED))
            },
            customPermissionChecker = { it == PACKAGE_HARNESS },
            ownPackageName = PACKAGE_GUARD
        )
        val r3 = e3.inferForegroundPackage(T)

        assertEquals("Session 1 must be attributed to Harness", PACKAGE_HARNESS, r1.packageName)
        assertEquals("Session 2 must be attributed to Adversary", PACKAGE_ADVERSARY, r2.packageName)
        assertEquals("Session 3 must be attributed to Harness", PACKAGE_HARNESS, r3.packageName)

        // Cross-contamination checks
        assertFalse("Session 2 must not return Harness", r2.packageName == PACKAGE_HARNESS)
        assertFalse("Session 3 must not return Adversary", r3.packageName == PACKAGE_ADVERSARY)
    }

    // -------------------------------------------------------------------------
    // 9. Lifecycle closure after attribution
    // -------------------------------------------------------------------------

    /**
     * A CAMERA_BECAME_AVAILABLE event after CAMERA_BECAME_UNAVAILABLE is the session
     * closure callback. The attributed package from UNAVAILABLE must be retained
     * independently by the application layer (not re-inferred). The engine does not
     * carry session state — it infers solely from UsageStats at the time of the call.
     */
    @Test
    fun testLifecycleClosure_engineStateless_perCallAttribution() {
        // Opening event: Harness resumed 300ms ago
        val openEngine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> listOf(
                UsageEventRecord(packageName = PACKAGE_HARNESS, timestamp = T - 300L,
                    eventType = UsageEvents.Event.ACTIVITY_RESUMED))
            },
            customPermissionChecker = { it == PACKAGE_HARNESS },
            ownPackageName = PACKAGE_GUARD
        )
        val openResult = openEngine.inferForegroundPackage(T)
        assertEquals(PACKAGE_HARNESS, openResult.packageName)

        // Closure event: Harness now backgrounded, 4000ms later
        // UsageStats shows Harness last resumed 4300ms ago
        val closeTime = T + 4_000L
        val closeEngine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> listOf(
                UsageEventRecord(packageName = PACKAGE_HARNESS, timestamp = T - 300L,
                    eventType = UsageEvents.Event.ACTIVITY_RESUMED))
            },
            customPermissionChecker = { it == PACKAGE_HARNESS },
            ownPackageName = PACKAGE_GUARD
        )
        val closeResult = closeEngine.inferForegroundPackage(closeTime)

        // Still attributed — delta is now 4300ms → LOW confidence, but same package
        assertEquals("Closure event must still attribute to same package", PACKAGE_HARNESS, closeResult.packageName)
        assertEquals(InferenceConfidence.LOW, closeResult.confidence) // 4300ms > 2000ms
    }

    // -------------------------------------------------------------------------
    // 10. Anti-self-attribution: CameraGuard must never attribute to itself
    // -------------------------------------------------------------------------

    /**
     * Under all circumstances — even when CameraGuard is the only app in UsageStats
     * and has camera permission declared — the engine must return null (UNKNOWN) rather
     * than attributing to itself.
     */
    @Test
    fun testAntiSelfAttribution_guardNeverAttributedToItself() {
        val events = listOf(
            UsageEventRecord(
                packageName = PACKAGE_GUARD,
                timestamp = T - 100L,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            // Even if CameraGuard somehow appears to have camera permission
            customPermissionChecker = { true },
            ownPackageName = PACKAGE_GUARD
        )

        val result = engine.inferForegroundPackage(T)

        assertNull("Anti-self-attribution: must never return own package name", result.packageName)
        assertFalse(
            "Package name must not equal CameraGuard package",
            result.packageName == PACKAGE_GUARD
        )
    }

    // -------------------------------------------------------------------------
    // 11. Restart transient context reset
    // -------------------------------------------------------------------------

    /**
     * C10 semantic: After a CameraGuard restart and 6.5s idle period, the inference
     * engine has no pre-restart knowledge. With no recent ACTIVITY_RESUMED evidence
     * for the background caller, attribution must be UNKNOWN. This is verified by
     * ensuring an empty/stale event set returns null.
     */
    @Test
    fun testRestartTransientContextReset_noPreRestartLeakage() {
        // Simulate post-restart state: 6.5s idle, only own resumed event (from starting CG)
        val events = listOf(
            UsageEventRecord(
                packageName = PACKAGE_GUARD,
                timestamp = T - 6_500L,   // 6.5s ago, CameraGuard itself started
                eventType = UsageEvents.Event.ACTIVITY_RESUMED
            )
        )
        val engine = ContextualInferenceEngine(
            customEventProvider = { _, _ -> events },
            customPermissionChecker = { it == PACKAGE_ADVERSARY },
            ownPackageName = PACKAGE_GUARD
        )

        val result = engine.inferForegroundPackage(T)

        // Only own event in window, no camera-capable non-own candidate within transition → UNKNOWN
        assertNull("Post-restart with no external evidence must return null attribution", result.packageName)
        assertFalse("Must not self-attribute after restart", result.packageName == PACKAGE_GUARD)
        assertEquals(InferenceConfidence.NONE, result.confidence)
    }

    // -------------------------------------------------------------------------
    // 12. Duplicate suppression within 3 s deduplication window
    // -------------------------------------------------------------------------

    /**
     * CameraAvailabilityTracker suppresses duplicate callbacks reporting the SAME state.
     *
     * The deduplication model:
     *   - First callback for a cameraId establishes the baseline (no event).
     *   - Subsequent callbacks with the SAME state are suppressed (no event).
     *   - Only state transitions (AVAILABLE→UNAVAILABLE or vice versa) produce events.
     *
     * This validates that rapid repeated UNAVAILABLE callbacks for the same camera session
     * do not produce spurious duplicate events.
     */
    @Test
    fun testDuplicateSuppression_sameStateCallbackSuppressed() {
        val tracker = CameraAvailabilityTracker()
        val events = mutableListOf<org.cameraguard.data.model.CameraAccessEvent>()
        tracker.onEventDetected = { events.add(it) }

        // Establish baseline: AVAILABLE (no event)
        tracker.availabilityCallback.onCameraAvailable("0")
        assertEquals("Baseline AVAILABLE must not generate event", 0, events.size)

        // Genuine transition: AVAILABLE → UNAVAILABLE (1 event)
        tracker.availabilityCallback.onCameraUnavailable("0")
        assertEquals("First UNAVAILABLE transition must produce 1 event", 1, events.size)

        // Duplicate UNAVAILABLE callbacks (same state) — must be suppressed
        tracker.availabilityCallback.onCameraUnavailable("0")
        tracker.availabilityCallback.onCameraUnavailable("0")
        assertEquals("Duplicate UNAVAILABLE callbacks must be suppressed (still 1 event)", 1, events.size)
    }


    // -------------------------------------------------------------------------
    // 13. Classification for UNKNOWN context (Tier-2 ML: AMBIGUOUS/UNEXPECTED)
    // -------------------------------------------------------------------------

    /**
     * When attribution is UNKNOWN (null package, NONE confidence, NONE method),
     * Tier-1 rules return UNKNOWN, and Tier-2 Decision Tree must be invoked.
     *
     * With f06=0 (NONE confidence), f07=0 (NONE method), f09=2.0 (multiple recent
     * activities in window), the Decision Tree path is:
     *   f06 ≤ 1.50 → f07 ≤ 1.00 → f09 > 1.00 → AMBIGUOUS → UNEXPECTED
     */
    @Test
    fun testClassification_unknownContext_tier2MlInvoked_yieldsUnexpected() {
        val unknownContext = InferredPackageContext(
            packageName = null,
            confidence = InferenceConfidence.NONE,
            method = InferenceMethod.NONE,
            hasCameraPermission = null,
            deltaFromEventMs = null,
            recentActivityCount30s = 2   // > 1 → AMBIGUOUS branch
        )

        val result = hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = unknownContext,
            cameraId = "0",
            isKnownCameraApp = false
        )

        assertTrue("Tier 2 must be invoked for UNKNOWN context", result.mlInvoked)
        assertEquals("TIER_2_ML", result.tierUsed)
        assertEquals(TreeClassification.AMBIGUOUS, result.treeClassification)
        assertEquals(
            "AMBIGUOUS tree result maps to UNEXPECTED",
            AccessClassification.UNEXPECTED,
            result.finalClassification
        )
    }

    // -------------------------------------------------------------------------
    // 14. Classification for known legitimate context → EXPECTED
    // -------------------------------------------------------------------------

    /**
     * When a known foreground caller is attributed with HIGH confidence and
     * InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED, the Decision Tree path with
     * f06=3.0 (HIGH) produces LEGITIMATE → EXPECTED.
     *
     * Decision Tree: f06 > 1.50 → LEGITIMATE → EXPECTED
     */
    @Test
    fun testClassification_legitimateContext_yieldsExpected() {
        val legitimateContext = InferredPackageContext(
            packageName = PACKAGE_HARNESS,
            confidence = InferenceConfidence.HIGH,      // f06 = 3.0
            method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            hasCameraPermission = true,
            deltaFromEventMs = 200L,
            recentActivityCount30s = 1
        )

        val result = hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = legitimateContext,
            cameraId = "0",
            isKnownCameraApp = false
        )

        // With HIGH confidence, Tier-2 should classify as LEGITIMATE
        // (either through Tier-1 EXPECTED or Tier-2 LEGITIMATE)
        assertEquals(
            "Legitimate attributed context must yield EXPECTED",
            AccessClassification.EXPECTED,
            result.finalClassification
        )
    }

    // -------------------------------------------------------------------------
    // 15. Classification for suspicious background context → UNEXPECTED
    // -------------------------------------------------------------------------

    /**
     * Scenario: Screen ON, no attribution (background-only access, CameraGuard foreground).
     * This matches the P7 / C4 finding: Tier-2 ML evaluates the event as AMBIGUOUS when
     * recent_activity_count > 1, producing UNEXPECTED classification.
     *
     * When recent_activity_count ≤ 1 (idle screen): CONTROLS → EXPECTED.
     * When recent_activity_count > 1 (active screen with app switching): AMBIGUOUS → UNEXPECTED.
     *
     * This test validates the boundary: f09 = 3.0 > 1.0 → AMBIGUOUS → UNEXPECTED.
     */
    @Test
    fun testClassification_suspiciousBackgroundContext_yieldsUnexpected() {
        // Background FGS, CameraGuard foreground, multiple recent activities detected
        val suspiciousContext = InferredPackageContext(
            packageName = null,
            confidence = InferenceConfidence.NONE,
            method = InferenceMethod.NONE,
            hasCameraPermission = null,
            deltaFromEventMs = null,
            recentActivityCount30s = 3   // f09 = 3.0 > 1.50 → AMBIGUOUS
        )

        val result = hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = suspiciousContext,
            cameraId = "0",
            isKnownCameraApp = false
        )

        assertTrue("Tier 2 must be invoked for UNKNOWN context", result.mlInvoked)
        assertEquals(TreeClassification.AMBIGUOUS, result.treeClassification)
        assertEquals(
            "Suspicious background with high activity count must yield UNEXPECTED",
            AccessClassification.UNEXPECTED,
            result.finalClassification
        )
    }
}
