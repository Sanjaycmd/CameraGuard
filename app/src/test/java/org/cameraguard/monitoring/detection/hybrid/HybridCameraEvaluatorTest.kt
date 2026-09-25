package org.cameraguard.monitoring.detection.hybrid

import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.data.model.ScreenInteractivityState
import org.cameraguard.monitoring.detection.CameraRuleEvaluator
import org.cameraguard.monitoring.telemetry.InferredPackageContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HybridCameraEvaluatorTest {

    private lateinit var ruleEvaluator: CameraRuleEvaluator
    private lateinit var hybridEvaluator: HybridCameraEvaluator

    @Before
    fun setup() {
        ruleEvaluator = CameraRuleEvaluator()
        hybridEvaluator = HybridCameraEvaluator(ruleEvaluator)
    }

    private fun createDefaultFeatures(
        f01ScreenState: Double = 1.0,
        f02IsInteractive: Double = 1.0,
        f03IsLocked: Double = 0.0,
        f04PermClamped: Double = -1.0,
        f05KnownCameraApp: Double = 1.0,
        f06PackageConfidence: Double = 3.0,
        f07InferenceMethod: Double = 1.0,
        f08DeltaResumedMs: Double = 120.0,
        f09RecentActivityCount: Double = 1.0,
        f10CameraId: Double = 0.0,
        f11IsBackCamera: Double = 1.0
    ): ProductionT0FeatureVector {
        return ProductionT0FeatureVector(
            f01ScreenState = f01ScreenState,
            f02IsInteractive = f02IsInteractive,
            f03IsLocked = f03IsLocked,
            f04PermClamped = f04PermClamped,
            f05KnownCameraApp = f05KnownCameraApp,
            f06PackageConfidence = f06PackageConfidence,
            f07InferenceMethod = f07InferenceMethod,
            f08DeltaResumedMs = f08DeltaResumedMs,
            f09RecentActivityCount = f09RecentActivityCount,
            f10CameraId = f10CameraId,
            f11IsBackCamera = f11IsBackCamera
        )
    }

    /**
     * Test A — Tier 1 definitive LEGITIMATE / EXPECTED
     *
     * Verification:
     * - Tier 1 = EXPECTED
     * - ML invoked = false
     * - mlResult = null
     * - Final = EXPECTED
     */
    @Test
    fun testA_tier1DefinitiveLegitimate_returnsImmediatelyWithoutMl() {
        val inferredContext = InferredPackageContext(
            packageName = "com.google.android.GoogleCamera",
            confidence = InferenceConfidence.HIGH,
            method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            hasCameraPermission = true,
            deltaFromEventMs = 120L,
            recentActivityCount30s = 1
        )

        val result = hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = inferredContext,
            cameraId = "0",
            isKnownCameraApp = true
        )

        assertEquals("TIER_1_RULE", result.tierUsed)
        assertEquals("EXPECTED", result.deterministicResult)
        assertNull("ML must not produce a result when Tier 1 is definitive", result.mlResult)
        assertFalse("ML must NOT be invoked when Tier 1 is definitive", result.mlInvoked)
        assertEquals(AccessClassification.EXPECTED, result.finalClassification)
        assertNull(result.treeClassification)
    }

    /**
     * Test B — Tier 1 definitive CONTROLS / EXPECTED (Lifecycle Closure)
     *
     * Verification:
     * - Tier 1 = EXPECTED
     * - ML invoked = false
     * - mlResult = null
     * - Final = EXPECTED
     */
    @Test
    fun testB_tier1DefinitiveControls_cameraClosure_returnsImmediatelyWithoutMl() {
        val inferredContext = InferredPackageContext(
            packageName = "com.google.android.GoogleCamera",
            confidence = InferenceConfidence.NONE,
            method = InferenceMethod.NONE,
            hasCameraPermission = null,
            deltaFromEventMs = 0L,
            recentActivityCount30s = 0
        )

        val result = hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_AVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = inferredContext,
            cameraId = "0",
            isKnownCameraApp = true
        )

        assertEquals("TIER_1_RULE", result.tierUsed)
        assertEquals("EXPECTED", result.deterministicResult)
        assertNull(result.mlResult)
        assertFalse("ML must NOT be invoked for camera closure", result.mlInvoked)
        assertEquals(AccessClassification.EXPECTED, result.finalClassification)
    }

    /**
     * Test B2 — Tier 1 definitive UNEXPECTED
     *
     * Verification:
     * - Tier 1 = UNEXPECTED (Screen Off, Device Locked, Missing Permission)
     * - ML invoked = false
     * - Final = UNEXPECTED
     */
    @Test
    fun testB2_tier1DefinitiveUnexpected_screenOff_returnsImmediatelyWithoutMl() {
        val inferredContext = InferredPackageContext(
            packageName = "com.stealth.camera",
            confidence = InferenceConfidence.HIGH,
            method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            hasCameraPermission = true,
            deltaFromEventMs = 50L,
            recentActivityCount30s = 1
        )

        val result = hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_OFF,
            inferredContext = inferredContext,
            cameraId = "0",
            isKnownCameraApp = false
        )

        assertEquals("TIER_1_RULE", result.tierUsed)
        assertEquals("UNEXPECTED", result.deterministicResult)
        assertNull(result.mlResult)
        assertFalse(result.mlInvoked)
        assertEquals(AccessClassification.UNEXPECTED, result.finalClassification)
    }

    /**
     * Test C — Tier 1 UNKNOWN -> Tier 2 ML invoked
     *
     * In production, third-party apps have unverified runtime permissions (hasCameraPermission == null),
     * causing Tier 1 to produce UNKNOWN. Tier 2 ML resolves the contextual ambiguity.
     */
    @Test
    fun testC1_tier1Unknown_mlResolvesLegitimate() {
        // Unverified permission -> Tier 1 outputs UNKNOWN.
        // Package confidence HIGH (3.0) -> F06 > 1.50 -> Tree outputs LEGITIMATE.
        val inferredContext = InferredPackageContext(
            packageName = "com.thirdparty.camera",
            confidence = InferenceConfidence.HIGH,
            method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            hasCameraPermission = null, // Sandbox cannot verify runtime permission
            deltaFromEventMs = 150L,
            recentActivityCount30s = 1
        )

        val result = hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = inferredContext,
            cameraId = "0",
            isKnownCameraApp = true
        )

        assertEquals("TIER_2_ML", result.tierUsed)
        assertEquals("UNKNOWN", result.deterministicResult)
        assertEquals("LEGITIMATE", result.mlResult)
        assertTrue("ML MUST be invoked when Tier 1 is UNKNOWN", result.mlInvoked)
        assertEquals(AccessClassification.EXPECTED, result.finalClassification)
        assertEquals(TreeClassification.LEGITIMATE, result.treeClassification)
    }

    @Test
    fun testC2_tier1Unknown_mlResolvesAmbiguous() {
        // Uncorrelated background access with activity in lookback window:
        // Package confidence NONE (0.0) -> F06 <= 1.50
        // Inference method NONE (0.0) -> F07 <= 1.00
        // Recent activity count 2 (> 1.00) -> Tree outputs AMBIGUOUS -> UNEXPECTED alert.
        val inferredContext = InferredPackageContext(
            packageName = null,
            confidence = InferenceConfidence.NONE,
            method = InferenceMethod.NONE,
            hasCameraPermission = null,
            deltaFromEventMs = null,
            recentActivityCount30s = 2
        )

        val result = hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = inferredContext,
            cameraId = "0",
            isKnownCameraApp = false
        )

        assertEquals("TIER_2_ML", result.tierUsed)
        assertEquals("UNKNOWN", result.deterministicResult)
        assertEquals("AMBIGUOUS", result.mlResult)
        assertTrue(result.mlInvoked)
        assertEquals(AccessClassification.UNEXPECTED, result.finalClassification)
        assertEquals(TreeClassification.AMBIGUOUS, result.treeClassification)
    }

    @Test
    fun testC3_tier1Unknown_mlResolvesControls() {
        // Inactive control condition (e.g. controlled negative test or unpolled idle state):
        // Package confidence NONE (0.0) -> F06 <= 1.50
        // Inference method NONE (0.0) -> F07 <= 1.00
        // Recent activity count 0 (<= 1.00) -> Tree outputs CONTROLS -> EXPECTED benign control.
        val inferredContext = InferredPackageContext(
            packageName = null,
            confidence = InferenceConfidence.NONE,
            method = InferenceMethod.NONE,
            hasCameraPermission = null,
            deltaFromEventMs = null,
            recentActivityCount30s = 0
        )

        val result = hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = inferredContext,
            cameraId = "0",
            isKnownCameraApp = false
        )

        assertEquals("TIER_2_ML", result.tierUsed)
        assertEquals("UNKNOWN", result.deterministicResult)
        assertEquals("CONTROLS", result.mlResult)
        assertTrue(result.mlInvoked)
        assertEquals(AccessClassification.EXPECTED, result.finalClassification)
        assertEquals(TreeClassification.CONTROLS, result.treeClassification)
    }

    /**
     * Test D — ML cannot override definitive rule
     *
     * Constructs cases where the ML tree would theoretically produce LEGITIMATE,
     * but Tier 1 rules produce UNEXPECTED. ML must NEVER override a definitive rule.
     */
    @Test
    fun testD_mlCannotOverrideDefinitiveRule_screenOff() {
        val inferredContext = InferredPackageContext(
            packageName = "com.android.camera",
            confidence = InferenceConfidence.HIGH, // F06 = 3.0 -> Tree alone would say LEGITIMATE
            method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            hasCameraPermission = true,
            deltaFromEventMs = 100L,
            recentActivityCount30s = 1
        )

        val result = hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_OFF, // Deterministic Rule 1 triggers!
            inferredContext = inferredContext,
            cameraId = "0",
            isKnownCameraApp = true
        )

        assertEquals("TIER_1_RULE", result.tierUsed)
        assertEquals("UNEXPECTED", result.deterministicResult)
        assertFalse("ML must NOT run when Tier 1 rule is definitive", result.mlInvoked)
        assertNull(result.mlResult)
        assertEquals(AccessClassification.UNEXPECTED, result.finalClassification)
    }

    @Test
    fun testD_mlCannotOverrideDefinitiveRule_deviceLocked() {
        val inferredContext = InferredPackageContext(
            packageName = "com.android.camera",
            confidence = InferenceConfidence.HIGH,
            method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            hasCameraPermission = true,
            deltaFromEventMs = 100L,
            recentActivityCount30s = 1
        )

        val result = hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_LOCKED, // Deterministic Rule 2 triggers!
            inferredContext = inferredContext,
            cameraId = "0",
            isKnownCameraApp = true
        )

        assertEquals("TIER_1_RULE", result.tierUsed)
        assertEquals("UNEXPECTED", result.deterministicResult)
        assertFalse(result.mlInvoked)
        assertNull(result.mlResult)
        assertEquals(AccessClassification.UNEXPECTED, result.finalClassification)
    }

    @Test
    fun testD_mlCannotOverrideDefinitiveRule_missingPermission() {
        val inferredContext = InferredPackageContext(
            packageName = "com.malicious.app",
            confidence = InferenceConfidence.HIGH,
            method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            hasCameraPermission = false, // Deterministic Rule 3 triggers!
            deltaFromEventMs = 100L,
            recentActivityCount30s = 1
        )

        val result = hybridEvaluator.evaluate(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = inferredContext,
            cameraId = "0",
            isKnownCameraApp = false
        )

        assertEquals("TIER_1_RULE", result.tierUsed)
        assertEquals("UNEXPECTED", result.deterministicResult)
        assertFalse(result.mlInvoked)
        assertNull(result.mlResult)
        assertEquals(AccessClassification.UNEXPECTED, result.finalClassification)
    }

    /**
     * Test E — F04 enforcement
     *
     * Production feature construction MUST clamp F04 to UNVERIFIED (-1.0).
     * Any other value must immediately throw an IllegalArgumentException.
     */
    @Test
    fun testE_f04Enforcement_strictlyClampedToNegativeOne() {
        val inferredContext = InferredPackageContext(
            packageName = "com.example.app",
            confidence = InferenceConfidence.HIGH,
            method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            hasCameraPermission = true, // Even if harness or mock passes true
            deltaFromEventMs = 100L,
            recentActivityCount30s = 1
        )

        val vector = hybridEvaluator.buildProductionFeatureVector(
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            inferredContext = inferredContext,
            cameraId = "0",
            isKnownCameraApp = true
        )

        assertEquals(-1.0, vector.f04PermClamped, 0.0)

        // Attempting to construct a vector with F04 != -1.0 must fail fast
        try {
            createDefaultFeatures(f04PermClamped = 1.0)
            fail("Expected IllegalArgumentException when f04 != -1.0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("LEAKAGE VIOLATION") == true)
        }

        try {
            createDefaultFeatures(f04PermClamped = 0.0)
            fail("Expected IllegalArgumentException when f04 != -1.0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("LEAKAGE VIOLATION") == true)
        }
    }

    /**
     * Test F — Forbidden feature protection
     *
     * Production feature construction must NOT expose retrospective features (F12–F20),
     * scenario IDs, ground-truth labels, session metadata, or harness state.
     */
    @Test
    fun testF_forbiddenFeatureProtection() {
        // 1. Verify standard production feature list contains strictly 11 T0 features
        assertEquals(11, ProductionT0FeatureVector.FEATURE_NAMES.size)
        ProductionT0FeatureVector.assertFeatureSafety(ProductionT0FeatureVector.FEATURE_NAMES)

        // 2. Verify all prohibited terms trigger immediate detection
        val prohibitedExamples = listOf(
            listOf("session_duration_ms"),
            listOf("explicit_user_start_action"),
            listOf("explicit_user_stop_action"),
            listOf("internal_harness_state"),
            listOf("harness_fg_service_active"),
            listOf("time_to_session_close_ms"),
            listOf("app_visibility_at_trigger"),
            listOf("backgrounded_during_session"),
            listOf("returned_to_foreground"),
            listOf("scenario_id"),
            listOf("ground_truth"),
            listOf("f12_session_duration"),
            listOf("f20_returned")
        )

        for (prohibited in prohibitedExamples) {
            try {
                ProductionT0FeatureVector.assertFeatureSafety(prohibited)
                fail("Expected IllegalArgumentException for prohibited feature name: $prohibited")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("LEAKAGE DETECTED") == true)
            }
        }
    }

    /**
     * Test G — Decision Tree parity with Phase 4.7 audited tree
     *
     * Verifies each branch of the native Decision Tree implementation against
     * the exact mathematical specification from Phase 4.7:
     *
     * F06 <= 1.50
     *     F07 <= 1.00
     *         F09 <= 1.00 -> CONTROLS
     *         F09 > 1.00  -> AMBIGUOUS
     *     F07 > 1.00
     *         F09 <= 1.50
     *             F08 <= 59.5 ms -> AMBIGUOUS
     *             F08 > 59.5 ms  -> LEGITIMATE
     *         F09 > 1.50  -> AMBIGUOUS
     * F06 > 1.50 -> LEGITIMATE
     */
    @Test
    fun testG_decisionTreeParity_branch1_controls() {
        // F06 = 0.0 (<= 1.50), F07 = 0.0 (<= 1.00), F09 = 0.0 (<= 1.00)
        val features = createDefaultFeatures(
            f06PackageConfidence = 0.0,
            f07InferenceMethod = 0.0,
            f09RecentActivityCount = 0.0
        )
        val outcome = ProductionDecisionTree.evaluate(features)
        assertEquals(TreeClassification.CONTROLS, outcome)
    }

    @Test
    fun testG_decisionTreeParity_branch2_ambiguous() {
        // F06 = 0.0 (<= 1.50), F07 = 1.0 (<= 1.00), F09 = 2.0 (> 1.00)
        val features = createDefaultFeatures(
            f06PackageConfidence = 0.0,
            f07InferenceMethod = 1.0,
            f09RecentActivityCount = 2.0
        )
        val outcome = ProductionDecisionTree.evaluate(features)
        assertEquals(TreeClassification.AMBIGUOUS, outcome)
    }

    @Test
    fun testG_decisionTreeParity_branch3_ambiguous_shortDelta() {
        // F06 = 1.0 (<= 1.50), F07 = 2.0 (> 1.00), F09 = 1.0 (<= 1.50), F08 = 30.0 (<= 59.5)
        val features = createDefaultFeatures(
            f06PackageConfidence = 1.0,
            f07InferenceMethod = 2.0,
            f09RecentActivityCount = 1.0,
            f08DeltaResumedMs = 30.0
        )
        val outcome = ProductionDecisionTree.evaluate(features)
        assertEquals(TreeClassification.AMBIGUOUS, outcome)
    }

    @Test
    fun testG_decisionTreeParity_branch4_legitimate_longDelta() {
        // F06 = 1.0 (<= 1.50), F07 = 2.0 (> 1.00), F09 = 1.0 (<= 1.50), F08 = 120.0 (> 59.5)
        val features = createDefaultFeatures(
            f06PackageConfidence = 1.0,
            f07InferenceMethod = 2.0,
            f09RecentActivityCount = 1.0,
            f08DeltaResumedMs = 120.0
        )
        val outcome = ProductionDecisionTree.evaluate(features)
        assertEquals(TreeClassification.LEGITIMATE, outcome)
    }

    @Test
    fun testG_decisionTreeParity_branch5_ambiguous_highActivity() {
        // F06 = 1.0 (<= 1.50), F07 = 2.0 (> 1.00), F09 = 3.0 (> 1.50)
        val features = createDefaultFeatures(
            f06PackageConfidence = 1.0,
            f07InferenceMethod = 2.0,
            f09RecentActivityCount = 3.0
        )
        val outcome = ProductionDecisionTree.evaluate(features)
        assertEquals(TreeClassification.AMBIGUOUS, outcome)
    }

    @Test
    fun testG_decisionTreeParity_branch6_legitimate_mediumConfidence() {
        // F06 = 2.0 (> 1.50) -> LEGITIMATE
        val features = createDefaultFeatures(
            f06PackageConfidence = 2.0
        )
        val outcome = ProductionDecisionTree.evaluate(features)
        assertEquals(TreeClassification.LEGITIMATE, outcome)
    }

    @Test
    fun testG_decisionTreeParity_branch7_legitimate_highConfidence() {
        // F06 = 3.0 (> 1.50) -> LEGITIMATE
        val features = createDefaultFeatures(
            f06PackageConfidence = 3.0
        )
        val outcome = ProductionDecisionTree.evaluate(features)
        assertEquals(TreeClassification.LEGITIMATE, outcome)
    }
}
