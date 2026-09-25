package org.cameratestharness.research.hybrid

/**
 * Classification outcome within the CameraGuard research hybrid framework.
 */
enum class HybridClassification {
    LEGITIMATE,
    AMBIGUOUS,
    CONTROL,
    UNKNOWN
}

/**
 * Indicates which tier of the hybrid architecture made the final classification decision.
 */
enum class DecisionTier {
    RULE,
    ML
}

/**
 * Complete decision provenance for every hybrid classification.
 * Tracks tier execution, intermediate verdicts, and ML invocation state.
 */
data class HybridDecisionResult(
    val tierUsed: DecisionTier,
    val deterministicResult: HybridClassification,
    val mlResult: HybridClassification?,
    val finalResult: HybridClassification,
    val mlInvoked: Boolean
)

/**
 * Production-safe T0 feature contract for the ML contextual resolver.
 * Strictly guarantees:
 * 1. F04 is clamped to UNVERIFIED (-1.0).
 * 2. Zero retrospective (T2) features (duration, close timestamps, fgs, etc.) are present.
 * 3. Zero scenario/label/target fields are present.
 */
data class ResearchFeatureVector(
    val f01ScreenState: Double,
    val f02IsInteractive: Double,
    val f03IsLocked: Double,
    val f04PermClamped: Double,
    val f05KnownCameraApp: Double,
    val f06PackageConfidence: Double,
    val f07InferenceMethod: Double,
    val f08DeltaResumedMs: Double,
    val f09RecentActivityCount: Double,
    val f10CameraId: Double,
    val f11IsBackCamera: Double
) {
    init {
        require(f04PermClamped == -1.0) {
            "LEAKAGE VIOLATION: F04 must remain clamped to UNVERIFIED (-1.0) in production mode, got $f04PermClamped"
        }
    }

    fun toArray(): DoubleArray {
        return doubleArrayOf(
            f01ScreenState,
            f02IsInteractive,
            f03IsLocked,
            f04PermClamped,
            f05KnownCameraApp,
            f06PackageConfidence,
            f07InferenceMethod,
            f08DeltaResumedMs,
            f09RecentActivityCount,
            f10CameraId,
            f11IsBackCamera
        )
    }

    companion object {
        val FEATURE_NAMES = listOf(
            "f01_screen_state",
            "f02_is_interactive",
            "f03_is_locked",
            "f04_perm_clamped",
            "f05_known_camera_app",
            "f06_package_confidence",
            "f07_inference_method",
            "f08_delta_resumed_ms",
            "f09_recent_activity_count",
            "f10_camera_id",
            "f11_is_back_camera"
        )

        val PROHIBITED_FEATURE_TERMS = listOf(
            "duration", "stop", "start_action", "stop_action",
            "fgs", "visibility", "returned", "target", "scenario",
            "ground_truth", "exclusion", "f12", "f13", "f14", "f15",
            "f16", "f17", "f18", "f19", "f20"
        )

        fun assertFeatureSafety(names: List<String>) {
            for (name in names) {
                val lower = name.lowercase()
                for (term in PROHIBITED_FEATURE_TERMS) {
                    if (lower.contains(term)) {
                        throw IllegalArgumentException("LEAKAGE DETECTED: Prohibited substring '$term' in feature '$name'")
                    }
                }
            }
        }
    }
}

/**
 * Research-only two-tier hybrid classifier prototype.
 *
 * Architectural Invariant:
 * Tier 1: Deterministic security rules.
 *         If deterministicResult != UNKNOWN -> preserve rule decision. The ML model MUST NOT run.
 * Tier 2: Machine Learning contextual classifier (Decision Tree).
 *         If deterministicResult == UNKNOWN -> invoke Decision Tree.
 *
 * The ML layer CANNOT override a definitive Tier-1 decision under any circumstance.
 */
class HybridResearchClassifier(
    private val decisionTreeEvaluator: (ResearchFeatureVector) -> HybridClassification = ::evaluateDefaultDecisionTree
) {

    /**
     * Executes two-tier classification while recording full provenance.
     */
    fun classify(
        features: ResearchFeatureVector,
        deterministicResult: HybridClassification
    ): HybridDecisionResult {
        // Case A: Deterministic definitive result (RULE Tier)
        if (deterministicResult != HybridClassification.UNKNOWN) {
            return HybridDecisionResult(
                tierUsed = DecisionTier.RULE,
                deterministicResult = deterministicResult,
                mlResult = null,
                finalResult = deterministicResult,
                mlInvoked = false
            )
        }

        // Case B: Deterministic UNKNOWN (ML Tier)
        val mlDecision = decisionTreeEvaluator(features)
        return HybridDecisionResult(
            tierUsed = DecisionTier.ML,
            deterministicResult = HybridClassification.UNKNOWN,
            mlResult = mlDecision,
            finalResult = mlDecision,
            mlInvoked = true
        )
    }

    companion object {
        /**
         * Native Kotlin execution of the Decision Tree trained in Phase 4.7.
         * Translates the transparent tree structure into deterministic conditional branches.
         */
        fun evaluateDefaultDecisionTree(features: ResearchFeatureVector): HybridClassification {
            // Root: package attribution confidence
            if (features.f06PackageConfidence <= 1.50) {
                // Low / Ambiguous attribution or negative control
                if (features.f11IsBackCamera <= 0.00) {
                    // No camera hardware acquired (sentinel -1.0) -> CONTROL
                    return HybridClassification.CONTROL
                } else {
                    // Camera hardware was acquired, check activity count & resumed delta
                    if (features.f09RecentActivityCount <= 1.50) {
                        return if (features.f08DeltaResumedMs <= 59.50) {
                            HybridClassification.AMBIGUOUS
                        } else {
                            HybridClassification.LEGITIMATE
                        }
                    } else {
                        return HybridClassification.AMBIGUOUS
                    }
                }
            } else {
                // High confidence user foreground app correlation -> LEGITIMATE
                return HybridClassification.LEGITIMATE
            }
        }
    }
}
