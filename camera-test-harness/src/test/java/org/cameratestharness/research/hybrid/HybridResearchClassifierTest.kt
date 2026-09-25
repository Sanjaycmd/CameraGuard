package org.cameratestharness.research.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Automated test suite for Phase 4.7 Hybrid Rules + ML research architecture.
 *
 * Verifies:
 * 1. Rule override safety (Tests 1 - 4):
 *    - Definitive RULE results (LEGITIMATE, CONTROL) cannot be overridden by ML.
 *    - ML is strictly invoked only when Tier 1 produces UNKNOWN.
 * 2. Feature safety & leakage prevention (Tests 5 - 6):
 *    - F04 must remain clamped to UNVERIFIED (-1.0).
 *    - Zero retrospective T2 features or label terms enter the ML feature vector.
 * 3. Provenance and OOF integrity (Test 7).
 * 4. Model artifact integrity (Test 8).
 * 5. 3-way empirical comparison validation (Tests 9 - 10).
 */
class HybridResearchClassifierTest {

    private fun getTargetRawDir(): File? {
        val rawDir = File("../data/raw")
        val fallbackRawDir = File("data/raw")
        val targetRawDir = if (rawDir.exists()) rawDir else fallbackRawDir
        return if (targetRawDir.exists()) targetRawDir else null
    }

    private fun getHybridOutputDir(): File {
        val rawDir = getTargetRawDir() ?: File("data/raw")
        return File(rawDir.parentFile, "derived/phase4/hybrid")
    }

    private fun createSampleFeatureVector(f04: Double = -1.0): ResearchFeatureVector {
        return ResearchFeatureVector(
            f01ScreenState = 2.0,      // ON_UNLOCKED
            f02IsInteractive = 1.0,    // True
            f03IsLocked = 0.0,         // False
            f04PermClamped = f04,      // Must be -1.0
            f05KnownCameraApp = 0.0,   // False
            f06PackageConfidence = 2.0,// High
            f07InferenceMethod = 1.0,  // Stack
            f08DeltaResumedMs = 120.0, // Resumed delta
            f09RecentActivityCount = 1.0,
            f10CameraId = 0.0,         // Rear
            f11IsBackCamera = 1.0      // Back
        )
    }

    @Test
    fun `test 01 - if deterministic rule says LEGITIMATE ML cannot change it`() {
        // Even if ML would predict AMBIGUOUS or CONTROL, Tier 1 is authoritative
        var mlCalled = false
        val hybridClassifier = HybridResearchClassifier(
            decisionTreeEvaluator = {
                mlCalled = true
                HybridClassification.AMBIGUOUS // Intentionally contradictory ML verdict
            }
        )

        val features = createSampleFeatureVector()
        val result = hybridClassifier.classify(features, deterministicResult = HybridClassification.LEGITIMATE)

        assertEquals("Tier used must be RULE", DecisionTier.RULE, result.tierUsed)
        assertEquals("Deterministic result must be LEGITIMATE", HybridClassification.LEGITIMATE, result.deterministicResult)
        assertNull("ML result must be null", result.mlResult)
        assertEquals("Final result must be LEGITIMATE", HybridClassification.LEGITIMATE, result.finalResult)
        assertFalse("ML must not be invoked", result.mlInvoked)
        assertFalse("ML evaluator callback must not have been executed", mlCalled)
    }

    @Test
    fun `test 02 - if deterministic rule says CONTROL ML cannot change it`() {
        var mlCalled = false
        val hybridClassifier = HybridResearchClassifier(
            decisionTreeEvaluator = {
                mlCalled = true
                HybridClassification.LEGITIMATE // Intentionally contradictory ML verdict
            }
        )

        val features = createSampleFeatureVector()
        val result = hybridClassifier.classify(features, deterministicResult = HybridClassification.CONTROL)

        assertEquals("Tier used must be RULE", DecisionTier.RULE, result.tierUsed)
        assertEquals("Deterministic result must be CONTROL", HybridClassification.CONTROL, result.deterministicResult)
        assertNull("ML result must be null", result.mlResult)
        assertEquals("Final result must be CONTROL", HybridClassification.CONTROL, result.finalResult)
        assertFalse("ML must not be invoked", result.mlInvoked)
        assertFalse("ML evaluator callback must not have been executed", mlCalled)
    }

    @Test
    fun `test 03 - if deterministic rule says UNKNOWN ML is invoked`() {
        var mlCalled = false
        val hybridClassifier = HybridResearchClassifier(
            decisionTreeEvaluator = {
                mlCalled = true
                HybridClassification.AMBIGUOUS
            }
        )

        val features = createSampleFeatureVector()
        val result = hybridClassifier.classify(features, deterministicResult = HybridClassification.UNKNOWN)

        assertEquals("Tier used must be ML", DecisionTier.ML, result.tierUsed)
        assertEquals("Deterministic result was UNKNOWN", HybridClassification.UNKNOWN, result.deterministicResult)
        assertEquals("ML result is AMBIGUOUS", HybridClassification.AMBIGUOUS, result.mlResult)
        assertEquals("Final result is AMBIGUOUS", HybridClassification.AMBIGUOUS, result.finalResult)
        assertTrue("ML must be invoked", result.mlInvoked)
        assertTrue("ML evaluator callback must have been executed", mlCalled)
    }

    @Test
    fun `test 04 - ML output becomes final result only when deterministic result is UNKNOWN`() {
        val hybridClassifier = HybridResearchClassifier()
        val features = createSampleFeatureVector()

        // 1. Definite RULE cases: final matches deterministic
        val r1 = hybridClassifier.classify(features, HybridClassification.LEGITIMATE)
        assertEquals(HybridClassification.LEGITIMATE, r1.finalResult)
        assertFalse(r1.mlInvoked)

        val r2 = hybridClassifier.classify(features, HybridClassification.CONTROL)
        assertEquals(HybridClassification.CONTROL, r2.finalResult)
        assertFalse(r2.mlInvoked)

        // 2. UNKNOWN case: final matches ML
        val r3 = hybridClassifier.classify(features, HybridClassification.UNKNOWN)
        assertEquals(r3.mlResult, r3.finalResult)
        assertTrue(r3.mlInvoked)
    }

    @Test
    fun `test 05 - F04 must remain clamped to minus 1 point 0`() {
        // Valid vector with F04 = -1.0
        val valid = createSampleFeatureVector(-1.0)
        assertEquals(-1.0, valid.f04PermClamped, 0.001)

        // Invalid vector with F04 != -1.0 must throw IllegalArgumentException
        try {
            createSampleFeatureVector(1.0)
            fail("Expected exception when F04 is not -1.0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("LEAKAGE VIOLATION: F04 must remain clamped to UNVERIFIED"))
        }
    }

    @Test
    fun `test 06 - no T2 features or label terms enter the ML feature vector`() {
        val names = ResearchFeatureVector.FEATURE_NAMES
        assertEquals(11, names.size)

        // Must pass safety check
        ResearchFeatureVector.assertFeatureSafety(names)

        // Check each prohibited term explicitly
        val prohibited = ResearchFeatureVector.PROHIBITED_FEATURE_TERMS
        for (feat in names) {
            for (p in prohibited) {
                assertFalse("Feature '$feat' must not contain prohibited substring '$p'", feat.contains(p))
            }
        }
    }

    @Test
    fun `test 07 - hybrid provenance records complete tier tracking for every session`() {
        val hybridDir = getComparisonOutputDir()
        val provFile = File(hybridDir, "hybrid_provenance.csv")

        assertTrue("hybrid_provenance.csv must exist", provFile.exists())
        val lines = provFile.readLines()
        assertTrue("Must have header + records: actual ${lines.size}", lines.size > 100)

        val header = lines[0]
        assertTrue(header.contains("cohort") && header.contains("tier_used") && header.contains("ml_invoked"))

        // Check that RULE tier records have ml_invoked = False and ml_result = NOT_INVOKED
        val ruleLines = lines.filter { it.contains(",RULE,") }
        assertTrue(ruleLines.isNotEmpty())
        for (rl in ruleLines) {
            assertTrue("Rule tier line must show ml_invoked=False: $rl", rl.contains(",False,NOT_INVOKED,"))
        }

        // Check that ML tier records have ml_invoked = True and deterministic_result = UNKNOWN
        val mlLines = lines.filter { it.contains(",ML,") }
        assertTrue(mlLines.isNotEmpty())
        for (ml in mlLines) {
            assertTrue("ML tier line must show ml_invoked=True and UNKNOWN: $ml", ml.contains(",UNKNOWN,True,"))
        }
    }

    @Test
    fun `test 08 - decision tree artifact structure and JSON models exist and are valid`() {
        val hybridDir = getComparisonOutputDir()
        val txtFile = File(hybridDir, "decision_tree_structure.txt")
        val jsonFile = File(hybridDir, "decision_tree_model.json")
        val manifestFile = File(hybridDir, "hybrid_manifest.json")

        assertTrue("decision_tree_structure.txt must exist", txtFile.exists())
        val txt = txtFile.readText()
        assertTrue(txt.contains("FINAL_RESEARCH_FIT"))
        assertTrue(txt.contains("f06_package_confidence"))

        assertTrue("decision_tree_model.json must exist", jsonFile.exists())
        val json = jsonFile.readText()
        assertTrue(json.contains("\"artifact_label\": \"FINAL_RESEARCH_FIT\""))
        assertTrue(json.contains("\"tree_structure\""))

        assertTrue("hybrid_manifest.json must exist", manifestFile.exists())
        val manifest = manifestFile.readText()
        assertTrue(manifest.contains("\"candidate_model\": \"Decision Tree"))
        assertTrue(manifest.contains("\"verified_malware_samples\": 0"))
    }

    @Test
    fun `test 09 - three-way system comparison validates hybrid improvement over baseline and standalone ML`() {
        val hybridDir = getComparisonOutputDir()
        val metricsFile = File(hybridDir, "hybrid_metrics.csv")

        assertTrue("hybrid_metrics.csv must exist", metricsFile.exists())
        val lines = metricsFile.readLines()
        assertTrue("Must have at least 7 lines: actual ${lines.size}", lines.size >= 7)

        val t41Rules = lines.find { it.startsWith("TARGETED_41,System_A_Deterministic_Rules") }
        val t41Dt = lines.find { it.startsWith("TARGETED_41,System_B_Standalone_Decision_Tree") }
        val t41Hybrid = lines.find { it.startsWith("TARGETED_41,System_C_Hybrid_Rule_Decision_Tree") }

        assertNotNull("System A must be present in Targeted 41", t41Rules)
        assertNotNull("System B must be present in Targeted 41", t41Dt)
        assertNotNull("System C must be present in Targeted 41", t41Hybrid)

        // Baseline accuracy: ~60.98%
        assertTrue("Rules overall accuracy must be ~60.98%: $t41Rules", t41Rules!!.contains("0.609756"))

        // Standalone Decision Tree accuracy: ~90.24%
        assertTrue("Decision Tree accuracy must be ~90.24%: $t41Dt", t41Dt!!.contains("0.902439"))

        // Hybrid accuracy: ~92.68%
        assertTrue("Hybrid accuracy must be ~92.68%: $t41Hybrid", t41Hybrid!!.contains("0.926829"))

        // Hybrid false alarms must be 0
        assertTrue("Hybrid false alarms must be 0: $t41Hybrid", t41Hybrid.contains(",0,0,"))
    }

    @Test
    fun `test 10 - hybrid error analysis confirms zero critical false legitimate errors on targeted cohort`() {
        val hybridDir = getComparisonOutputDir()
        val errFile = File(hybridDir, "hybrid_error_analysis.csv")

        assertTrue("hybrid_error_analysis.csv must exist", errFile.exists())
        val lines = errFile.readLines()

        // In Targeted 41, there must be ZERO CRITICAL_FALSE_LEGITIMATE errors
        val t41Errors = lines.filter { it.startsWith("TARGETED_41") }
        for (err in t41Errors) {
            assertFalse("Targeted 41 must not have CRITICAL_FALSE_LEGITIMATE error: $err", err.contains("CRITICAL_FALSE_LEGITIMATE"))
        }

        // Exactly 3 non-acquisition edge cases where camera was not opened
        val nonAcqErrors = t41Errors.filter { it.contains("AMBIGUOUS_TO_CONTROLS_NON_ACQ") }
        assertEquals(3, nonAcqErrors.size)
    }

    private fun getComparisonOutputDir(): File {
        return getHybridOutputDir()
    }
}
