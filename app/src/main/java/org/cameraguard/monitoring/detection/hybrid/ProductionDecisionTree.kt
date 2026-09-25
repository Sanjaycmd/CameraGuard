package org.cameraguard.monitoring.detection.hybrid

/**
 * Three-class contextual policy outcome from the Phase 4.7 validated Decision Tree.
 */
enum class TreeClassification {
    LEGITIMATE,
    AMBIGUOUS,
    CONTROLS
}

/**
 * Native, zero-dependency implementation of the audited Phase 4.7 Decision Tree.
 *
 * Tree structure (depth <= 5):
 * F06 <= 1.50
 *     F07 <= 1.00
 *         F09 <= 1.00
 *             CONTROLS
 *         F09 > 1.00
 *             AMBIGUOUS
 *     F07 > 1.00
 *         F09 <= 1.50
 *             F08 <= 59.5 ms
 *                 AMBIGUOUS
 *             F08 > 59.5 ms
 *                 LEGITIMATE
 *         F09 > 1.50
 *             AMBIGUOUS
 * F06 > 1.50
 *     LEGITIMATE
 */
object ProductionDecisionTree {

    fun evaluate(features: ProductionT0FeatureVector): TreeClassification {
        // Level 1 split: f06_package_confidence <= 1.50
        return if (features.f06PackageConfidence <= 1.50) {
            // Level 2 split: f07_inference_method <= 1.00
            if (features.f07InferenceMethod <= 1.00) {
                // Level 3 split: f09_recent_activity_count <= 1.00
                if (features.f09RecentActivityCount <= 1.00) {
                    TreeClassification.CONTROLS
                } else {
                    TreeClassification.AMBIGUOUS
                }
            } else {
                // Level 2 split (f07_inference_method > 1.00): f09_recent_activity_count <= 1.50
                if (features.f09RecentActivityCount <= 1.50) {
                    // Level 3 split: f08_delta_resumed_ms <= 59.50
                    if (features.f08DeltaResumedMs <= 59.50) {
                        TreeClassification.AMBIGUOUS
                    } else {
                        TreeClassification.LEGITIMATE
                    }
                } else {
                    TreeClassification.AMBIGUOUS
                }
            }
        } else {
            TreeClassification.LEGITIMATE
        }
    }
}
