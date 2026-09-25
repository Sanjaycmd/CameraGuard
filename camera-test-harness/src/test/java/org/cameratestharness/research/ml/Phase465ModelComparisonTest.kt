package org.cameratestharness.research.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Automated test suite for Phase 4.6.5 Model Comparison & Risk Tradeoff Analysis.
 * Verifies:
 * 1. OOF Integrity: Zero session overlap across folds, exactly 1 OOF prediction per session.
 * 2. Hybrid Integrity: Resolution rate distinguished from ground-truth accuracy.
 * 3. Feature Integrity: Zero retrospective leakage, zero target/scenario leakage, F04 clamping.
 * 4. Dataset Integrity: 89 valid sessions, 3 legacy exclusions, 41 targeted cohort preserved.
 * 5. Comparison Artifacts: All 8 required comparison files generated and verified.
 */
class Phase465ModelComparisonTest {

    private fun getTargetRawDir(): File? {
        val rawDir = File("../data/raw")
        val fallbackRawDir = File("data/raw")
        val targetRawDir = if (rawDir.exists()) rawDir else fallbackRawDir
        return if (targetRawDir.exists()) targetRawDir else null
    }

    private fun getComparisonOutputDir(): File {
        val rawDir = getTargetRawDir() ?: File("data/raw")
        return File(rawDir.parentFile, "derived/phase4/comparison")
    }

    private fun getEvaluationOutputDir(): File {
        val rawDir = getTargetRawDir() ?: File("data/raw")
        return File(rawDir.parentFile, "derived/phase4/evaluation")
    }

    @Test
    fun `test 01 - dataset integrity preserves 89 valid sessions 3 legacy exclusions and 41 targeted sessions`() {
        val rawDir = getTargetRawDir() ?: return
        val records = Phase464DatasetPreparation.prepareMlDataset(rawDir)

        assertEquals(92, records.size)
        val included = records.filter { it.inclusionStatus == "INCLUDED" }
        val excluded = records.filter { it.inclusionStatus == "EXCLUDED" }
        val targeted = records.filter { it.cohort == "TARGETED_41" }

        assertEquals(89, included.size)
        assertEquals(3, excluded.size)
        assertEquals(41, targeted.size)

        for (t in targeted) {
            assertEquals("Targeted session ${t.sessionId} must be INCLUDED", "INCLUDED", t.inclusionStatus)
        }
    }

    @Test
    fun `test 02 - feature integrity verifies zero retrospective leakage and strict F04 clamping`() {
        val prodNames = MlSessionRecord.PRODUCTION_FEATURE_NAMES

        // 1. Prohibited retrospective substrings
        val prohibited = listOf("duration", "stop", "fgs", "visibility", "returned", "f12", "f13", "f14", "f15", "f16", "f17", "f18", "f19", "f20")
        for (feat in prodNames) {
            for (p in prohibited) {
                assertFalse("Production feature '$feat' must not contain retrospective term '$p'", feat.contains(p))
            }
        }

        // 2. Prohibited label/scenario substrings
        val labelTerms = listOf("target", "scenario", "ground_truth", "context", "policy")
        for (feat in prodNames) {
            for (term in labelTerms) {
                assertFalse("Production feature '$feat' must not contain label term '$term'", feat.contains(term))
            }
        }

        // 3. F04 clamped in production mode
        val rawDir = getTargetRawDir() ?: return
        val records = Phase464DatasetPreparation.prepareMlDataset(rawDir)
        for (r in records) {
            assertEquals(-1.0, r.f04PermClamped, 0.001)
        }
    }

    @Test
    fun `test 03 - OOF integrity audit confirms zero session duplicates and strict fold isolation`() {
        val compDir = getComparisonOutputDir()
        val oofFile = File(compDir, "oof_integrity_audit.csv")

        assertTrue("oof_integrity_audit.csv must exist", oofFile.exists())
        val lines = oofFile.readLines()
        assertTrue("oof_integrity_audit.csv must have header + rows", lines.size > 1)

        val header = lines[0]
        assertTrue(header.contains("task") && header.contains("oof_integrity_status"))

        for (i in 1 until lines.size) {
            val line = lines[i]
            assertTrue("Line $i must indicate VERIFIED_STRICT_OOF: $line", line.contains("VERIFIED_STRICT_OOF"))
            assertTrue("Line $i must indicate 0 duplicates: $line", line.contains(",0,0,False,False,"))
        }
    }

    @Test
    fun `test 04 - hybrid audit explicitly distinguishes UNKNOWN resolution from ground truth accuracy`() {
        val compDir = getComparisonOutputDir()
        val hybridFile = File(compDir, "hybrid_audit.csv")

        assertTrue("hybrid_audit.csv must exist", hybridFile.exists())
        val lines = hybridFile.readLines()
        assertTrue("hybrid_audit.csv must have records", lines.size >= 3) // header + 2 cohorts

        // Check Targeted 41 row
        val targetedRow = lines.find { it.startsWith("TARGETED_41") }
        assertNotNull("Targeted 41 row must exist in hybrid audit", targetedRow)

        // Resolution rate must be 1.0 (100% resolved)
        assertTrue("Targeted 41 must show 100% resolution of UNKNOWN", targetedRow!!.contains(",16,1.0,"))

        // True accuracy must be ~0.9268 (not 1.000)
        assertTrue("Targeted 41 ground truth accuracy must be ~0.9268: $targetedRow", targetedRow.contains("0.9268"))

        // Check Accumulated 89 row
        val accRow = lines.find { it.startsWith("ACCUMULATED_89") }
        assertNotNull("Accumulated 89 row must exist in hybrid audit", accRow)
        assertTrue("Accumulated 89 ground truth accuracy must be ~0.9213: $accRow", accRow!!.contains("0.9213"))
    }

    @Test
    fun `test 05 - feature dependency analysis verifies Task 2 deterministic separation rationale`() {
        val compDir = getComparisonOutputDir()
        val featFile = File(compDir, "feature_dependency_analysis.csv")

        assertTrue("feature_dependency_analysis.csv must exist", featFile.exists())
        val lines = featFile.readLines()
        assertTrue(lines.size >= 12) // header + 11 features

        val f10 = lines.find { it.contains("f10_camera_id") }
        assertNotNull(f10)
        assertTrue("F10 must be recognized as deterministic separator for Task 2: $f10", f10!!.contains(",True,"))

        val f11 = lines.find { it.contains("f11_is_back_camera") }
        assertNotNull(f11)
        assertTrue("F11 must be recognized as deterministic separator for Task 2: $f11", f11!!.contains(",True,"))

        val f04 = lines.find { it.contains("f04_perm_clamped") }
        assertNotNull(f04)
        assertTrue("F04 must be marked CLAMPED_SENTINEL_UNVERIFIED: $f04", f04!!.contains("CLAMPED_SENTINEL_UNVERIFIED"))
    }

    @Test
    fun `test 06 - error analysis verifies security impact categories`() {
        val compDir = getComparisonOutputDir()
        val errFile = File(compDir, "error_analysis.csv")

        assertTrue("error_analysis.csv must exist", errFile.exists())
        val lines = errFile.readLines()
        assertTrue(lines.size > 1) // header + errors

        // Verify categories exist
        val content = errFile.readText()
        assertTrue(content.contains("NON_ACQUISITION_CONTROL_COLLAPSE"))
        assertTrue(content.contains("CONSERVATIVE_FALSE_AMBIGUOUS"))
    }

    @Test
    fun `test 07 - model tradeoffs matrix exists with structured multi-criteria rows`() {
        val compDir = getComparisonOutputDir()
        val tradeFile = File(compDir, "model_tradeoffs.csv")

        assertTrue("model_tradeoffs.csv must exist", tradeFile.exists())
        val lines = tradeFile.readLines()
        assertTrue("Tradeoffs matrix must contain at least 8 criteria rows: actual ${lines.size}", lines.size >= 9)

        val content = tradeFile.readText()
        assertTrue(content.contains("Predictive Performance"))
        assertTrue(content.contains("Interpretability"))
        assertTrue(content.contains("Computational & Memory Overhead"))
        assertTrue(content.contains("Security Error Risks"))
        assertTrue(content.contains("Android-side Deployment Suitability"))
    }

    @Test
    fun `test 08 - comparison manifest verifies threat model statement and research findings`() {
        val compDir = getComparisonOutputDir()
        val manifestFile = File(compDir, "comparison_manifest.json")

        assertTrue("comparison_manifest.json must exist", manifestFile.exists())
        val content = manifestFile.readText()

        assertTrue("Must declare 0 verified malware samples", content.contains("\"verified_malware_samples\": 0"))
        assertTrue("Must include Task 2 audit explanation", content.contains("task2_perfect_accuracy_audit"))
        assertTrue("Must include Hybrid audit explanation", content.contains("task6_hybrid_100pct_audit"))
        assertTrue("Must include OOF integrity confirmation", content.contains("oof_integrity_audit"))
        assertTrue("Must include candidate recommendation for Phase 4.7", content.contains("candidate_recommendation_phase47"))
    }

    @Test
    fun `test 09 - all 8 required Phase 4 6 5 comparison artifacts exist`() {
        val compDir = getComparisonOutputDir()
        val expectedFiles = listOf(
            "comparison_metrics.csv",
            "comparison_fold_metrics.csv",
            "error_analysis.csv",
            "model_tradeoffs.csv",
            "hybrid_audit.csv",
            "oof_integrity_audit.csv",
            "feature_dependency_analysis.csv",
            "comparison_manifest.json"
        )

        for (fname in expectedFiles) {
            val f = File(compDir, fname)
            assertTrue("Required Phase 4.6.5 artifact '$fname' must exist", f.exists())
            assertTrue("Artifact '$fname' must not be empty", f.length() > 0)
        }
    }
}
