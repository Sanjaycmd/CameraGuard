package org.cameraguard.monitoring.detection.hybrid

import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.data.model.ScreenInteractivityState
import org.cameraguard.monitoring.detection.CameraRuleEvaluator
import org.cameraguard.monitoring.telemetry.InferredPackageContext

/**
 * Result of evaluating a camera access event through the two-tier hybrid architecture.
 */
data class HybridEvaluationResult(
    val finalClassification: AccessClassification,
    val explanation: String,
    val tierUsed: String,
    val deterministicResult: String,
    val mlResult: String?,
    val mlInvoked: Boolean,
    val treeClassification: TreeClassification? = null
)

/**
 * Two-tier hybrid classification engine integrating deterministic rules with
 * the validated Phase 4.7 Decision Tree.
 *
 * Architectural Invariants:
 * Tier 1: Deterministic security rules (frozen Phase 2 baseline [CameraRuleEvaluator]).
 *         If deterministicResult != UNKNOWN -> return rule decision immediately.
 *         The ML model MUST NOT run.
 * Tier 2: Machine Learning contextual classifier (validated Decision Tree).
 *         If deterministicResult == UNKNOWN -> invoke Decision Tree with real-time T0 features.
 *
 * The ML layer CANNOT override a definitive Tier-1 decision under any circumstance.
 */
class HybridCameraEvaluator(
    val ruleEvaluator: CameraRuleEvaluator = CameraRuleEvaluator(),
    private val decisionTreeEvaluator: (ProductionT0FeatureVector) -> TreeClassification = ProductionDecisionTree::evaluate
) {

    companion object {
        const val TIER_1_RULE = "TIER_1_RULE"
        const val TIER_2_ML = "TIER_2_ML"
    }

    /**
     * Evaluates camera event through the two-tier hybrid architecture:
     *
     * 1. Evaluates Tier 1 deterministic rules.
     * 2. If Tier 1 produces a definitive result (EXPECTED or UNEXPECTED), returns immediately.
     *    mlInvoked is false and mlResult is null.
     * 3. If Tier 1 produces UNKNOWN, constructs the production T0 feature vector (F04 strictly clamped
     *    to -1.0) and evaluates Tier 2 Decision Tree.
     */
    fun evaluate(
        rawEventType: RawCameraEventType,
        screenState: ScreenInteractivityState,
        inferredContext: InferredPackageContext,
        cameraId: String? = null,
        isKnownCameraApp: Boolean = false,
        featureVectorOverride: ProductionT0FeatureVector? = null
    ): HybridEvaluationResult {
        // Tier 1: Run deterministic rules
        val tier1Result = ruleEvaluator.evaluate(
            rawEventType = rawEventType,
            screenState = screenState,
            inferredContext = inferredContext
        )

        // Definitive Tier 1 rule match (EXPECTED or UNEXPECTED) -> return immediately without ML invocation
        if (tier1Result.classification != AccessClassification.UNKNOWN) {
            return HybridEvaluationResult(
                finalClassification = tier1Result.classification,
                explanation = tier1Result.explanation,
                tierUsed = TIER_1_RULE,
                deterministicResult = tier1Result.classification.name,
                mlResult = null,
                mlInvoked = false,
                treeClassification = null
            )
        }

        // Tier 1 was UNKNOWN -> invoke Tier 2 Decision Tree
        val features = featureVectorOverride ?: buildProductionFeatureVector(
            rawEventType = rawEventType,
            screenState = screenState,
            inferredContext = inferredContext,
            cameraId = cameraId,
            isKnownCameraApp = isKnownCameraApp
        )

        val treeResult = decisionTreeEvaluator(features)

        val (finalClassification, hybridExplanation) = when (treeResult) {
            TreeClassification.AMBIGUOUS -> {
                AccessClassification.UNEXPECTED to
                    "Hybrid ML Tier-2 classified event as AMBIGUOUS (suspicious/unattributed access). Deterministic rule was UNKNOWN: ${tier1Result.explanation}"
            }
            TreeClassification.LEGITIMATE -> {
                AccessClassification.EXPECTED to
                    "Hybrid ML Tier-2 classified event as LEGITIMATE (correlated user activity). Deterministic rule was UNKNOWN: ${tier1Result.explanation}"
            }
            TreeClassification.CONTROLS -> {
                AccessClassification.EXPECTED to
                    "Hybrid ML Tier-2 classified event as CONTROLS (non-acquisition control condition). Deterministic rule was UNKNOWN: ${tier1Result.explanation}"
            }
        }

        return HybridEvaluationResult(
            finalClassification = finalClassification,
            explanation = hybridExplanation,
            tierUsed = TIER_2_ML,
            deterministicResult = tier1Result.classification.name,
            mlResult = treeResult.name,
            mlInvoked = true,
            treeClassification = treeResult
        )
    }

    /**
     * Constructs a production-safe T0 feature vector from observable real-time telemetry.
     * Guaranteed: F04 is clamped to UNVERIFIED (-1.0).
     */
    fun buildProductionFeatureVector(
        rawEventType: RawCameraEventType,
        screenState: ScreenInteractivityState,
        inferredContext: InferredPackageContext,
        cameraId: String?,
        isKnownCameraApp: Boolean
    ): ProductionT0FeatureVector {
        val f01 = when (screenState) {
            ScreenInteractivityState.SCREEN_OFF -> 0.0
            ScreenInteractivityState.SCREEN_ON_UNLOCKED -> 1.0
            ScreenInteractivityState.SCREEN_ON_LOCKED -> 2.0
            ScreenInteractivityState.UNKNOWN -> -1.0
        }

        val f02 = when (screenState) {
            ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            ScreenInteractivityState.SCREEN_ON_LOCKED -> 1.0
            ScreenInteractivityState.SCREEN_OFF -> 0.0
            ScreenInteractivityState.UNKNOWN -> -1.0
        }

        val f03 = when (screenState) {
            ScreenInteractivityState.SCREEN_ON_LOCKED -> 1.0
            ScreenInteractivityState.SCREEN_ON_UNLOCKED,
            ScreenInteractivityState.SCREEN_OFF -> 0.0
            ScreenInteractivityState.UNKNOWN -> -1.0
        }

        // F04 is strictly clamped to UNVERIFIED (-1.0) in production.
        // Third-party camera permission state cannot be reliably inspected across Android UID sandbox.
        val f04 = ProductionT0FeatureVector.PERMISSION_UNVERIFIED

        val f05 = if (isKnownCameraApp) 1.0 else 0.0

        val f06 = when (inferredContext.confidence) {
            InferenceConfidence.NONE -> 0.0
            InferenceConfidence.LOW -> 1.0
            InferenceConfidence.MEDIUM -> 2.0
            InferenceConfidence.HIGH -> 3.0
        }

        val f07 = when (inferredContext.method) {
            InferenceMethod.NONE -> 0.0
            InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED -> 1.0
            InferenceMethod.USAGE_STATS_FALLBACK -> 1.0
            InferenceMethod.ACCESSIBILITY_WINDOW_CHANGE -> 3.0
            InferenceMethod.MANUAL_SYNTHETIC -> -1.0
        }

        val f08 = if (inferredContext.deltaFromEventMs != null && inferredContext.deltaFromEventMs >= 0L) {
            inferredContext.deltaFromEventMs.toDouble()
        } else {
            -1.0
        }

        val f09 = inferredContext.recentActivityCount30s.toDouble().coerceAtLeast(0.0)

        val f10 = when (cameraId) {
            "0" -> 0.0
            "1" -> 1.0
            else -> -1.0
        }

        val f11 = when (cameraId) {
            "0" -> 1.0
            "1" -> 0.0
            else -> -1.0
        }

        return ProductionT0FeatureVector(
            f01ScreenState = f01,
            f02IsInteractive = f02,
            f03IsLocked = f03,
            f04PermClamped = f04,
            f05KnownCameraApp = f05,
            f06PackageConfidence = f06,
            f07InferenceMethod = f07,
            f08DeltaResumedMs = f08,
            f09RecentActivityCount = f09,
            f10CameraId = f10,
            f11IsBackCamera = f11
        )
    }
}
