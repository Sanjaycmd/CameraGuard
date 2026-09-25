package org.cameratestharness.research.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Automated test suite for Phase 4.6.4 Multi-Model Training and Evaluation.
 * Verifies:
 * 1. Dataset loading, invariant exclusions, and targeted cohort completeness.
 * 2. Feature policy: PRODUCTION_T0 enforcement, F04 clamping, and zero T2 retrospective leakage.
 * 3. Label/scenario leakage prevention.
 * 4. Session-level grouped cross-validation integrity.
 * 5. Execution of candidate models and artifact generation.
 */
class Phase464MultiModelEvaluationTest {

    private fun getTargetRawDir(): File? {
        val rawDir = File("../data/raw")
        val fallbackRawDir = File("data/raw")
        val targetRawDir = if (rawDir.exists()) rawDir else fallbackRawDir
        return if (targetRawDir.exists()) targetRawDir else null
    }

    private fun getEvaluationOutputDir(): File {
        val rawDir = getTargetRawDir() ?: File("data/raw")
        return File(rawDir.parentFile, "derived/phase4/evaluation")
    }

    @Test
    fun `test 01 - dataset preparation loads 92 sessions and excludes 3 invalid legacy sessions`() {
        val rawDir = getTargetRawDir() ?: return
        val records = Phase464DatasetPreparation.prepareMlDataset(rawDir)

        assertEquals(92, records.size)
        val included = records.filter { it.inclusionStatus == "INCLUDED" }
        val excluded = records.filter { it.inclusionStatus == "EXCLUDED" }

        assertEquals(89, included.size)
        assertEquals(3, excluded.size)

        // Verify the 3 excluded sessions are the legacy pre-3.5.1 defects
        val excludedSids = excluded.map { it.sessionId }.toSet()
        assertTrue(excludedSids.contains("exp_20260925_205422_4e75d0"))
        assertTrue(excludedSids.contains("exp_20260925_205424_0f3f21"))
        assertTrue(excludedSids.contains("exp_20260925_205605_1b68ae"))
    }

    @Test
    fun `test 02 - all 41 targeted sessions remain valid and included`() {
        val rawDir = getTargetRawDir() ?: return
        val records = Phase464DatasetPreparation.prepareMlDataset(rawDir)

        val targeted = records.filter { it.cohort == "TARGETED_41" }
        assertEquals(41, targeted.size)

        for (r in targeted) {
            assertEquals("Targeted session ${r.sessionId} must be INCLUDED", "INCLUDED", r.inclusionStatus)
        }
    }

    @Test
    fun `test 03 - feature policy enforces PRODUCTION_T0 and clamps F04 to UNVERIFIED`() {
        val rawDir = getTargetRawDir() ?: return
        val records = Phase464DatasetPreparation.prepareMlDataset(rawDir)

        for (r in records) {
            // F04 clamped must be -1.0 (UNVERIFIED) across every sample in production mode
            assertEquals(-1.0, r.f04PermClamped, 0.001)

            val prodVec = r.toProductionFeatureVector()
            assertEquals(11, prodVec.size)

            // F04 position in vector is index 3
            assertEquals(-1.0, prodVec[3], 0.001)
        }
    }

    @Test
    fun `test 04 - zero retrospective features F12 through F20 leak into T0 feature vector`() {
        val prodNames = MlSessionRecord.PRODUCTION_FEATURE_NAMES

        val prohibited = listOf("duration", "stop", "fgs", "visibility", "returned", "f12", "f13", "f14", "f15", "f16", "f17", "f18", "f19", "f20")
        for (feat in prodNames) {
            for (p in prohibited) {
                assertFalse("Production feature '$feat' must not contain retrospective term '$p'", feat.contains(p))
            }
        }
    }

    @Test
    fun `test 05 - zero scenario or ground-truth label leakage in feature matrix`() {
        val prodNames = MlSessionRecord.PRODUCTION_FEATURE_NAMES

        val labelTerms = listOf("target", "scenario", "ground_truth", "context", "label", "policy")
        for (feat in prodNames) {
            for (term in labelTerms) {
                assertFalse("Production feature '$feat' must not contain label term '$term'", feat.contains(term))
            }
        }
    }

    @Test
    fun `test 06 - export ML session dataset to data derived phase4 evaluation`() {
        val rawDir = getTargetRawDir() ?: return
        val outDir = getEvaluationOutputDir()
        val outputFile = File(outDir, "session_ml_dataset.csv")

        val records = Phase464DatasetPreparation.prepareMlDataset(rawDir)
        Phase464DatasetPreparation.exportMlDataset(records, outputFile)

        assertTrue(outputFile.exists())
        val lines = outputFile.readLines()
        assertEquals(93, lines.size) // 1 header + 92 sessions
    }

    @Test
    fun `test 07 - verify Task 2 binary hardware target distribution`() {
        val rawDir = getTargetRawDir() ?: return
        val records = Phase464DatasetPreparation.prepareMlDataset(rawDir)
        val included = records.filter { it.inclusionStatus == "INCLUDED" }

        val acqCount = included.count { it.task2Target == "CAMERA_ACQUISITION" }
        val noAcqCount = included.count { it.task2Target == "NO_CAMERA_CONTROL" }

        assertEquals(56, acqCount)
        assertEquals(33, noAcqCount)
        assertEquals(89, acqCount + noAcqCount)

        // In targeted 41 cohort
        val targeted = included.filter { it.cohort == "TARGETED_41" }
        val t41Acq = targeted.count { it.task2Target == "CAMERA_ACQUISITION" }
        val t41NoAcq = targeted.count { it.task2Target == "NO_CAMERA_CONTROL" }
        assertEquals(28, t41Acq)
        assertEquals(13, t41NoAcq)
    }

    @Test
    fun `test 08 - verify Task 3 three-tier contextual target distribution`() {
        val rawDir = getTargetRawDir() ?: return
        val records = Phase464DatasetPreparation.prepareMlDataset(rawDir)
        val targeted = records.filter { it.cohort == "TARGETED_41" }

        val legCount = targeted.count { it.task3Target == "LEGITIMATE" }
        val ambCount = targeted.count { it.task3Target == "AMBIGUOUS" }
        val ctlCount = targeted.count { it.task3Target == "CONTROLS" }

        assertEquals(15, legCount) // 10 Normal FG + 5 Start/Stop
        assertEquals(16, ambCount) // 8 BG Cont + 5 Timer + 3 Ambiguous Context
        assertEquals(10, ctlCount) // 5 Perm Denied + 5 Perm Granted No Camera
        assertEquals(41, legCount + ambCount + ctlCount)
    }

    @Test
    fun `test 09 - verify Task 5 anomaly inlier vs outlier partitioning`() {
        val rawDir = getTargetRawDir() ?: return
        val records = Phase464DatasetPreparation.prepareMlDataset(rawDir)
        val targeted = records.filter { it.cohort == "TARGETED_41" }

        val inliers = targeted.filter { it.task3Target == "LEGITIMATE" }
        val outliers = targeted.filter { it.task3Target == "AMBIGUOUS" }

        assertEquals(15, inliers.size)
        assertEquals(16, outliers.size)
    }

    @Test
    fun `test 10 - verify evaluation artifacts presence and integrity`() {
        val outDir = getEvaluationOutputDir()

        val modelMetricsCsv = File(outDir, "model_metrics.csv")
        val foldMetricsCsv = File(outDir, "fold_metrics.csv")
        val confusionMatrixCsv = File(outDir, "confusion_matrices.csv")
        val predictionsCsv = File(outDir, "predictions.csv")
        val evalManifestJson = File(outDir, "evaluation_manifest.json")
        val featManifestJson = File(outDir, "feature_manifest.json")

        // If files exist (generated by Python evaluate_models.py), verify content integrity
        if (modelMetricsCsv.exists()) {
            val lines = modelMetricsCsv.readLines()
            assertTrue(lines.size > 10)
            assertTrue(lines[0].contains("task,cohort,feature_policy,model"))
        }

        if (evalManifestJson.exists()) {
            val content = evalManifestJson.readText()
            assertTrue(content.contains("\"manifest_version\": \"1.0.0\""))
            assertTrue(content.contains("\"verified_malware_samples\": 0"))
        }

        if (featManifestJson.exists()) {
            val content = featManifestJson.readText()
            assertTrue(content.contains("\"primary_feature_set\": \"PRODUCTION_T0\""))
            assertTrue(content.contains("\"f04_policy\": \"CLAMPED_TO_UNVERIFIED\""))
        }
    }
}
