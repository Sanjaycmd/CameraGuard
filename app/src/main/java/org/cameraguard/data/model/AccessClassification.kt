package org.cameraguard.data.model

/**
 * Transparent heuristic classification of a camera event based strictly
 * on observable Android framework telemetry.
 *
 * NOTE: CameraGuard does NOT claim to definitively determine malicious
 * camera access, but classifies events based on contextual plausibility.
 */
enum class AccessClassification {
    EXPECTED,
    UNEXPECTED,
    UNKNOWN
}
