package org.cameraguard.data.model

/**
 * Degree of confidence in the inferred caller package correlation.
 *
 * NOTE: Package identity through UsageStatsManager or ActivityManager is
 * strictly treated as an inferred temporal correlation, never as ground truth.
 */
enum class InferenceConfidence {
    HIGH,
    MEDIUM,
    LOW,
    NONE
}
