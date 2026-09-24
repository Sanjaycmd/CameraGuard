package org.cameraguard.data.model

/**
 * Technique utilized to infer the candidate application active around
 * the camera availability transition.
 */
enum class InferenceMethod {
    USAGE_STATS_ACTIVITY_RESUMED,
    USAGE_STATS_FALLBACK,
    ACCESSIBILITY_WINDOW_CHANGE,
    MANUAL_SYNTHETIC,
    NONE
}
