package org.cameratestharness.experiment

/**
 * Unified feature dataset record combining audit metadata, ground-truth label,
 * production T0 features, and retrospective T2 research fields.
 *
 * Guarantees structural separation between what is observable at activation time (T0)
 * and what is only known retrospectively (T2).
 */
data class FeatureDatasetRow(
    // 1. Audit & Provenance Metadata
    val sessionId: String,
    val auditScenarioId: String,
    val repetition: Int,
    val timestampIso: String,
    val datasetVersion: String,
    val inclusionStatus: String, // "INCLUDED" or "EXCLUDED"
    val exclusionReason: String?,

    // 2. Ground Truth Target (Separated from feature vector)
    val groundTruthContext: String,

    // 3. Real-Time Production Feature Vector (T0 Activation Window)
    val t0Features: T0ProductionFeatureVector,

    // 4. Retrospective / Forensic Fields (T2 Post-Closure Window, NEVER in T0 Model)
    val f12SessionDurationMs: Long,
    val f13ExplicitUserStartAction: String,
    val f14ExplicitUserStopAction: String,
    val f15InternalHarnessState: String,
    val f16HarnessFgServiceActive: Boolean,
    val f17TimeToSessionCloseMs: Long,
    val f18AppVisibilityAtTrigger: String,
    val f19BackgroundedDuringSession: Boolean,
    val f20ReturnedToForeground: Boolean
) {
    /**
     * Emits full CSV row containing audit metadata, target, T0 features, and T2 research fields.
     */
    fun toFullCsvRow(): String {
        val auditValues = listOf(
            sessionId,
            auditScenarioId,
            repetition.toString(),
            timestampIso,
            datasetVersion,
            inclusionStatus,
            exclusionReason ?: "NONE",
            groundTruthContext
        )

        val t0Values = t0Features.toCsvValues()

        val t2Values = listOf(
            f12SessionDurationMs.toString(),
            f13ExplicitUserStartAction,
            f14ExplicitUserStopAction,
            f15InternalHarnessState,
            f16HarnessFgServiceActive.toString(),
            f17TimeToSessionCloseMs.toString(),
            f18AppVisibilityAtTrigger,
            f19BackgroundedDuringSession.toString(),
            f20ReturnedToForeground.toString()
        )

        return (auditValues + t0Values + t2Values).joinToString(",")
    }

    /**
     * Emits production-only CSV row containing audit metadata, target, and T0 features.
     * Retrospective fields (F12-F20) are excluded.
     */
    fun toProductionCsvRow(): String {
        val auditValues = listOf(
            sessionId,
            auditScenarioId,
            repetition.toString(),
            timestampIso,
            datasetVersion,
            inclusionStatus,
            exclusionReason ?: "NONE",
            groundTruthContext
        )

        val t0Values = t0Features.toCsvValues()

        return (auditValues + t0Values).joinToString(",")
    }

    companion object {
        val AUDIT_HEADER = listOf(
            "session_id",
            "audit_scenario_id",
            "repetition",
            "timestamp_iso",
            "dataset_version",
            "inclusion_status",
            "exclusion_reason",
            "ground_truth_context"
        )

        val T2_RESEARCH_HEADER = listOf(
            "f12_session_duration_ms",
            "f13_explicit_user_start_action",
            "f14_explicit_user_stop_action",
            "f15_internal_harness_state",
            "f16_harness_fg_service_active",
            "f17_time_to_session_close_ms",
            "f18_app_visibility_at_trigger",
            "f19_backgrounded_during_session",
            "f20_returned_to_foreground"
        )

        val FULL_CSV_HEADER = (AUDIT_HEADER + T0ProductionFeatureVector.FEATURE_NAMES + T2_RESEARCH_HEADER).joinToString(",")

        val PRODUCTION_CSV_HEADER = (AUDIT_HEADER + T0ProductionFeatureVector.FEATURE_NAMES).joinToString(",")
    }
}
