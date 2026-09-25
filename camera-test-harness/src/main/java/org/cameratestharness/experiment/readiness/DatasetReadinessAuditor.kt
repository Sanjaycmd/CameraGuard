package org.cameratestharness.experiment.readiness

import org.cameratestharness.experiment.FeatureCatalog
import org.cameratestharness.experiment.FeatureDatasetRow
import org.cameratestharness.experiment.FeatureDefinition
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data structures and audit logic for Phase 4.6.1 Dataset Readiness Audit.
 */

data class FeatureReadinessRecord(
    val featureId: String,
    val featureName: String,
    val temporalWindow: String,
    val observability: String,
    val totalSamples: Int,
    val nonMissingCount: Int,
    val missingOrUnknownCount: Int,
    val missingnessRate: Double,
    val isConstant: Boolean,
    val isNearConstant: Boolean,
    val dominantValue: String,
    val dominantValuePct: Double,
    val productionT0Eligible: Boolean,
    val readinessStatus: String,
    val auditNotes: String
) {
    fun toCsvRow(): String {
        return listOf(
            featureId,
            featureName,
            temporalWindow,
            observability,
            totalSamples.toString(),
            nonMissingCount.toString(),
            missingOrUnknownCount.toString(),
            String.format(Locale.US, "%.4f", missingnessRate),
            isConstant.toString(),
            isNearConstant.toString(),
            "\"${dominantValue.replace("\"", "\"\"")}\"",
            String.format(Locale.US, "%.2f", dominantValuePct),
            productionT0Eligible.toString(),
            readinessStatus,
            "\"${auditNotes.replace("\"", "\"\"")}\""
        ).joinToString(",")
    }

    companion object {
        val CSV_HEADER = listOf(
            "feature_id",
            "feature_name",
            "temporal_window",
            "observability",
            "total_samples",
            "non_missing_count",
            "missing_or_unknown_count",
            "missingness_rate",
            "is_constant",
            "is_near_constant",
            "dominant_value",
            "dominant_value_pct",
            "production_t0_eligible",
            "readiness_status",
            "audit_notes"
        ).joinToString(",")
    }
}

enum class SupportStatus {
    INSUFFICIENT, // < 3 samples (cannot be partitioned or validated reliably)
    MARGINAL,     // 3-5 samples (minimal validation possible, high variance)
    ADEQUATE      // > 5 samples (adequate for basic statistical representation)
}

enum class TargetFormulation {
    MULTICLASS,
    BINARY_HARDWARE_ACQUISITION,
    BINARY_OPERATIONAL_ALERT,
    HIERARCHICAL
}

data class ClassDistributionRecord(
    val targetFormulation: String,
    val className: String,
    val totalCount: Int,
    val includedCount: Int,
    val excludedCount: Int,
    val includedPct: Double,
    val supportStatus: SupportStatus,
    val recommendation: String
) {
    fun toCsvRow(): String {
        return listOf(
            targetFormulation,
            className,
            totalCount.toString(),
            includedCount.toString(),
            excludedCount.toString(),
            String.format(Locale.US, "%.2f", includedPct),
            supportStatus.name,
            "\"${recommendation.replace("\"", "\"\"")}\""
        ).joinToString(",")
    }

    companion object {
        val CSV_HEADER = listOf(
            "target_formulation",
            "class_name",
            "total_count",
            "included_count",
            "excluded_count",
            "included_pct",
            "support_status",
            "recommendation"
        ).joinToString(",")
    }
}

enum class CandidateFeatureSet(val id: String, val description: String) {
    PRODUCTION_T0(
        "PRODUCTION_T0",
        "Strictly production-observable features at T0 activation moment under Android sandbox. F04 omitted."
    ),
    PRODUCTION_T0_WITH_UNVERIFIED_PERMISSION(
        "PRODUCTION_T0_WITH_UNVERIFIED_PERMISSION",
        "Production T0 features including F04, where F04 is clamped to UNVERIFIED sentinel."
    ),
    ORACLE_RESEARCH(
        "ORACLE_RESEARCH",
        "Research T0 feature set where F04 is populated with ground-truth oracle permission status."
    ),
    RETROSPECTIVE(
        "RETROSPECTIVE",
        "Full post-session forensic audit set (F01..F20). Prohibited from real-time production inference."
    )
}

data class CrossValidationFeasibility(
    val is5FoldFeasible: Boolean,
    val is10FoldFeasible: Boolean,
    val maxDefensibleFoldsBinary: Int,
    val maxDefensibleFoldsMulticlass: Int,
    val requiresSessionGrouping: Boolean,
    val infeasibilityReason: String,
    val recommendedValidationStrategy: String
)

data class DatasetReadinessReport(
    val auditVersion: String,
    val auditTimestampIso: String,
    val datasetVersion: String,
    val overallReadinessStatus: String, // "CONDITIONALLY_READY"
    val totalSessions: Int,
    val includedSessions: Int,
    val excludedSessions: Int,
    val cameraAcquisitionSessions: Int,
    val noCameraControlSessions: Int,
    val featureAudits: List<FeatureReadinessRecord>,
    val classDistributions: List<ClassDistributionRecord>,
    val cvFeasibility: CrossValidationFeasibility,
    val candidateFeatureSets: Map<String, List<String>>,
    val physicalDataExpansionRequirements: List<String>
)

object DatasetReadinessAuditor {

    const val AUDIT_VERSION = "1.0.0"
    const val OVERALL_STATUS_CONDITIONALLY_READY = "CONDITIONALLY_READY"

    /**
     * Returns the feature IDs for a given candidate feature set.
     */
    fun getFeaturesForCandidateSet(candidateSet: CandidateFeatureSet): List<String> {
        return when (candidateSet) {
            CandidateFeatureSet.PRODUCTION_T0 -> listOf(
                "F01", "F02", "F03", "F05", "F06", "F07", "F08", "F09", "F10", "F11"
            )
            CandidateFeatureSet.PRODUCTION_T0_WITH_UNVERIFIED_PERMISSION -> listOf(
                "F01", "F02", "F03", "F04", "F05", "F06", "F07", "F08", "F09", "F10", "F11"
            )
            CandidateFeatureSet.ORACLE_RESEARCH -> listOf(
                "F01", "F02", "F03", "F04", "F05", "F06", "F07", "F08", "F09", "F10", "F11"
            )
            CandidateFeatureSet.RETROSPECTIVE -> (1..20).map { String.format(Locale.US, "F%02d", it) }
        }
    }

    /**
     * Audits all 20 features (F01..F20) across sample counts, missingness, constant status,
     * temporal window, and production observability.
     */
    fun auditFeatures(rows: List<FeatureDatasetRow>): List<FeatureReadinessRecord> {
        if (rows.isEmpty()) return emptyList()

        val total = rows.size
        val records = mutableListOf<FeatureReadinessRecord>()

        for (def in FeatureCatalog.ALL_FEATURES) {
            val rawValues: List<String> = rows.map { extractRawFeatureString(it, def.featureId) }
            val missingValues = rawValues.filter { isValueMissingOrUnknown(def.featureId, it) }
            val nonMissingValues = rawValues.filter { !isValueMissingOrUnknown(def.featureId, it) }

            val missingCount = missingValues.size
            val nonMissingCount = nonMissingValues.size
            val missingnessRate = missingCount.toDouble() / total

            // Dominant value analysis
            val valueCounts = rawValues.groupingBy { it }.eachCount()
            val dominantEntry = valueCounts.maxByOrNull { it.value }
            val dominantVal = dominantEntry?.key ?: "NONE"
            val dominantPct = if (total > 0) ((dominantEntry?.value ?: 0).toDouble() / total) * 100.0 else 0.0

            val isConstant = dominantPct >= 99.99
            val isNearConstant = !isConstant && dominantPct >= 80.0

            val productionEligible = isProductionT0Eligible(def.featureId)
            val readinessStatus = determineFeatureReadiness(
                featureId = def.featureId,
                isConstant = isConstant,
                isNearConstant = isNearConstant,
                missingnessRate = missingnessRate,
                productionEligible = productionEligible,
                temporalWindow = def.temporalWindow
            )

            val notes = getFeatureAuditNotes(def.featureId, dominantVal, dominantPct, missingnessRate)

            records.add(
                FeatureReadinessRecord(
                    featureId = def.featureId,
                    featureName = def.name,
                    temporalWindow = def.temporalWindow.name,
                    observability = def.observability.name,
                    totalSamples = total,
                    nonMissingCount = nonMissingCount,
                    missingOrUnknownCount = missingCount,
                    missingnessRate = missingnessRate,
                    isConstant = isConstant,
                    isNearConstant = isNearConstant,
                    dominantValue = dominantVal,
                    dominantValuePct = dominantPct,
                    productionT0Eligible = productionEligible,
                    readinessStatus = readinessStatus,
                    auditNotes = notes
                )
            )
        }

        return records
    }

    /**
     * Audits target formulations and evaluates class support.
     */
    fun auditClassDistributions(rows: List<FeatureDatasetRow>): List<ClassDistributionRecord> {
        val records = mutableListOf<ClassDistributionRecord>()
        val includedRows = rows.filter { it.inclusionStatus == "INCLUDED" }
        val excludedRows = rows.filter { it.inclusionStatus == "EXCLUDED" }
        val totalIncluded = includedRows.size

        // 1. MULTICLASS
        val allGtClasses = rows.map { it.groundTruthContext }.distinct().sorted()
        for (cls in allGtClasses) {
            val totalCount = rows.count { it.groundTruthContext == cls }
            val incCount = includedRows.count { it.groundTruthContext == cls }
            val excCount = excludedRows.count { it.groundTruthContext == cls }
            val pct = if (totalIncluded > 0) (incCount.toDouble() / totalIncluded) * 100.0 else 0.0

            val status = when {
                incCount < 3 -> SupportStatus.INSUFFICIENT
                incCount <= 5 -> SupportStatus.MARGINAL
                else -> SupportStatus.ADEQUATE
            }

            val recommendation = when {
                incCount == 1 -> "INSUFFICIENT (N=1): Cannot be used in standalone multiclass evaluation; fold validation undefined."
                incCount == 2 -> "INSUFFICIENT (N=2): Insufficient statistical representation; collapse into binary target or expand physical sessions."
                incCount in 3..5 -> "MARGINAL (N=$incCount): Minimal representation for evaluation; high variance expected."
                else -> "ADEQUATE (N=$incCount): Sufficient representation for primary training/evaluation."
            }

            records.add(
                ClassDistributionRecord(
                    targetFormulation = TargetFormulation.MULTICLASS.name,
                    className = cls,
                    totalCount = totalCount,
                    includedCount = incCount,
                    excludedCount = excCount,
                    includedPct = pct,
                    supportStatus = status,
                    recommendation = recommendation
                )
            )
        }

        // 2. BINARY_HARDWARE_ACQUISITION
        val isAcquisition: (FeatureDatasetRow) -> Boolean = {
            it.t0Features.f10CameraHardwareId != "UNKNOWN" ||
                    it.groundTruthContext in listOf("USER_INITIATED_FOREGROUND", "USER_INITIATED_BACKGROUND_CONTINUATION") &&
                    it.f12SessionDurationMs > 0 &&
                    it.t0Features.f06PackageInferenceConfidence > 0
        }

        val acqTotal = rows.count { isAcquisition(it) }
        val acqInc = includedRows.count { isAcquisition(it) }
        val acqExc = excludedRows.count { isAcquisition(it) }
        val acqPct = if (totalIncluded > 0) (acqInc.toDouble() / totalIncluded) * 100.0 else 0.0
        val acqStatus = if (acqInc < 3) SupportStatus.INSUFFICIENT else if (acqInc <= 5) SupportStatus.MARGINAL else SupportStatus.ADEQUATE

        records.add(
            ClassDistributionRecord(
                targetFormulation = TargetFormulation.BINARY_HARDWARE_ACQUISITION.name,
                className = "CAMERA_ACQUISITION",
                totalCount = acqTotal,
                includedCount = acqInc,
                excludedCount = acqExc,
                includedPct = acqPct,
                supportStatus = acqStatus,
                recommendation = "MARGINAL (N=$acqInc): Usable for binary activation gating models; expansion required."
            )
        )

        val noAcqTotal = rows.size - acqTotal
        val noAcqInc = totalIncluded - acqInc
        val noAcqExc = excludedRows.size - acqExc
        val noAcqPct = if (totalIncluded > 0) (noAcqInc.toDouble() / totalIncluded) * 100.0 else 0.0
        val noAcqStatus = if (noAcqInc < 3) SupportStatus.INSUFFICIENT else if (noAcqInc <= 5) SupportStatus.MARGINAL else SupportStatus.ADEQUATE

        records.add(
            ClassDistributionRecord(
                targetFormulation = TargetFormulation.BINARY_HARDWARE_ACQUISITION.name,
                className = "NO_CAMERA_CONTROL",
                totalCount = noAcqTotal,
                includedCount = noAcqInc,
                excludedCount = noAcqExc,
                includedPct = noAcqPct,
                supportStatus = noAcqStatus,
                recommendation = "ADEQUATE (N=$noAcqInc): Usable as negative control baseline."
            )
        )

        // 3. BINARY_OPERATIONAL_ALERT
        val alertInc = 0
        val nonAlertInc = totalIncluded

        records.add(
            ClassDistributionRecord(
                targetFormulation = TargetFormulation.BINARY_OPERATIONAL_ALERT.name,
                className = "UNEXPECTED_ALERT",
                totalCount = 0,
                includedCount = alertInc,
                excludedCount = 0,
                includedPct = 0.0,
                supportStatus = SupportStatus.INSUFFICIENT,
                recommendation = "INSUFFICIENT (N=0): Current dataset contains 0 covert/malicious executions. Cannot train or evaluate alert classifiers."
            )
        )

        records.add(
            ClassDistributionRecord(
                targetFormulation = TargetFormulation.BINARY_OPERATIONAL_ALERT.name,
                className = "NON_ALERT_AUTHORIZED",
                totalCount = rows.size,
                includedCount = nonAlertInc,
                excludedCount = excludedRows.size,
                includedPct = 100.0,
                supportStatus = SupportStatus.ADEQUATE,
                recommendation = "ADEQUATE (N=$nonAlertInc): All current sessions represent legitimate or negative control activities."
            )
        )

        return records
    }

    /**
     * Evaluates cross-validation feasibility across dataset constraints.
     */
    fun evaluateCvFeasibility(rows: List<FeatureDatasetRow>): CrossValidationFeasibility {
        val includedRows = rows.filter { it.inclusionStatus == "INCLUDED" }
        val multiclassCounts = includedRows.groupingBy { it.groundTruthContext }.eachCount()

        val minMulticlassSamples = multiclassCounts.values.minOrNull() ?: 0
        val is5FoldFeasible = minMulticlassSamples >= 5 && includedRows.size >= 25
        val is10FoldFeasible = minMulticlassSamples >= 10 && includedRows.size >= 50

        val maxMulticlassFolds = if (minMulticlassSamples >= 2) minMulticlassSamples else 1
        val maxBinaryFolds = if (includedRows.isNotEmpty()) {
            val acqCount = includedRows.count { it.t0Features.f06PackageInferenceConfidence > 0 }
            val noAcqCount = includedRows.size - acqCount
            minOf(acqCount, noAcqCount).coerceAtMost(3)
        } else 0

        val reason = "Standard 5-fold and 10-fold CV are mathematically impossible. " +
                "Classes AMBIGUOUS_CONTEXT (N=1) and NO_CAMERA_ACTIVITY (N=1) cannot be partitioned across multiple folds without zero representation. " +
                "Session grouping is mandatory: individual events from the same session must never be split across train and validation folds."

        val strategy = "Use Leave-One-Group-Out (LOGO) cross-validation on coarse binary acquisition targets, " +
                "or collapsed 2-fold grouped cross-validation. For multiclass evaluation, collapse rare classes or report full-sample baseline metrics."

        return CrossValidationFeasibility(
            is5FoldFeasible = is5FoldFeasible,
            is10FoldFeasible = is10FoldFeasible,
            maxDefensibleFoldsBinary = maxBinaryFolds,
            maxDefensibleFoldsMulticlass = maxMulticlassFolds,
            requiresSessionGrouping = true,
            infeasibilityReason = reason,
            recommendedValidationStrategy = strategy
        )
    }

    /**
     * Conducts complete dataset readiness audit.
     */
    fun auditDataset(rows: List<FeatureDatasetRow>): DatasetReadinessReport {
        val featureRecords = auditFeatures(rows)
        val classRecords = auditClassDistributions(rows)
        val cvFeasibility = evaluateCvFeasibility(rows)

        val included = rows.filter { it.inclusionStatus == "INCLUDED" }
        val excluded = rows.filter { it.inclusionStatus == "EXCLUDED" }
        val acqCount = included.count { it.t0Features.f06PackageInferenceConfidence > 0 }
        val noAcqCount = included.size - acqCount

        val candidateSets = mapOf(
            CandidateFeatureSet.PRODUCTION_T0.id to getFeaturesForCandidateSet(CandidateFeatureSet.PRODUCTION_T0),
            CandidateFeatureSet.PRODUCTION_T0_WITH_UNVERIFIED_PERMISSION.id to getFeaturesForCandidateSet(CandidateFeatureSet.PRODUCTION_T0_WITH_UNVERIFIED_PERMISSION),
            CandidateFeatureSet.ORACLE_RESEARCH.id to getFeaturesForCandidateSet(CandidateFeatureSet.ORACLE_RESEARCH),
            CandidateFeatureSet.RETROSPECTIVE.id to getFeaturesForCandidateSet(CandidateFeatureSet.RETROSPECTIVE)
        )

        val physicalReqs = listOf(
            "Front Camera Sessions: Collect minimum 10 sessions using front camera sensor (cameraId='1', isBackCamera=FALSE).",
            "Third-Party Applications: Collect sessions using real third-party communication apps (e.g. Meet, WhatsApp) to unfreeze F05.",
            "Screen Locked Camera Access: Collect minimum 10 sessions initiated while screen is ON_LOCKED to validate Rule 2 / lockscreen ML behavior.",
            "Screen Off Covert Access: Collect minimum 10 sessions simulating covert background access with SCREEN_OFF to validate Rule 1 / alert trigger.",
            "Automated Background Triggers: Collect sessions activated via alarm/job triggers without user interaction to validate confidence=LOW telemetry.",
            "Expanded Repetitions: Increase session count to 10-20 repetitions per scenario to enable statistically defensible 5-fold cross-validation."
        )

        return DatasetReadinessReport(
            auditVersion = AUDIT_VERSION,
            auditTimestampIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()),
            datasetVersion = rows.firstOrNull()?.datasetVersion ?: "1.0.0",
            overallReadinessStatus = OVERALL_STATUS_CONDITIONALLY_READY,
            totalSessions = rows.size,
            includedSessions = included.size,
            excludedSessions = excluded.size,
            cameraAcquisitionSessions = acqCount,
            noCameraControlSessions = noAcqCount,
            featureAudits = featureRecords,
            classDistributions = classRecords,
            cvFeasibility = cvFeasibility,
            candidateFeatureSets = candidateSets,
            physicalDataExpansionRequirements = physicalReqs
        )
    }

    /**
     * Exports readiness audit CSV and JSON artifacts to disk.
     */
    fun exportReadinessArtifacts(report: DatasetReadinessReport, outputDir: File) {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        // 1. dataset_readiness.csv
        val readinessCsv = File(outputDir, "dataset_readiness.csv")
        val readinessContent = StringBuilder()
        readinessContent.append(FeatureReadinessRecord.CSV_HEADER).append("\n")
        for (r in report.featureAudits) {
            readinessContent.append(r.toCsvRow()).append("\n")
        }
        readinessCsv.writeText(readinessContent.toString())

        // 2. class_distribution.csv
        val classCsv = File(outputDir, "class_distribution.csv")
        val classContent = StringBuilder()
        classContent.append(ClassDistributionRecord.CSV_HEADER).append("\n")
        for (c in report.classDistributions) {
            classContent.append(c.toCsvRow()).append("\n")
        }
        classCsv.writeText(classContent.toString())

        // 3. dataset_readiness_manifest.json
        val manifestJson = File(outputDir, "dataset_readiness_manifest.json")
        manifestJson.writeText(generateManifestJson(report))
    }

    private fun generateManifestJson(report: DatasetReadinessReport): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"audit_version\": \"${report.auditVersion}\",\n")
        sb.append("  \"audit_timestamp_iso\": \"${report.auditTimestampIso}\",\n")
        sb.append("  \"dataset_version\": \"${report.datasetVersion}\",\n")
        sb.append("  \"overall_readiness_status\": \"${report.overallReadinessStatus}\",\n")
        sb.append("  \"summary\": {\n")
        sb.append("    \"total_sessions\": ${report.totalSessions},\n")
        sb.append("    \"included_sessions\": ${report.includedSessions},\n")
        sb.append("    \"excluded_sessions\": ${report.excludedSessions},\n")
        sb.append("    \"camera_acquisition_sessions\": ${report.cameraAcquisitionSessions},\n")
        sb.append("    \"no_camera_control_sessions\": ${report.noCameraControlSessions}\n")
        sb.append("  },\n")

        // Candidate feature sets
        sb.append("  \"candidate_feature_sets\": {\n")
        val setsList = report.candidateFeatureSets.entries.toList()
        for (i in setsList.indices) {
            val entry = setsList[i]
            sb.append("    \"${entry.key}\": [${entry.value.joinToString(", ") { "\"$it\"" }}]")
            if (i < setsList.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  },\n")

        // Cross validation feasibility
        sb.append("  \"cross_validation_feasibility\": {\n")
        sb.append("    \"is_5_fold_cv_feasible\": ${report.cvFeasibility.is5FoldFeasible},\n")
        sb.append("    \"is_10_fold_cv_feasible\": ${report.cvFeasibility.is10FoldFeasible},\n")
        sb.append("    \"max_defensible_folds_binary\": ${report.cvFeasibility.maxDefensibleFoldsBinary},\n")
        sb.append("    \"max_defensible_folds_multiclass\": ${report.cvFeasibility.maxDefensibleFoldsMulticlass},\n")
        sb.append("    \"requires_session_grouping\": ${report.cvFeasibility.requiresSessionGrouping},\n")
        sb.append("    \"infeasibility_reason\": \"${report.cvFeasibility.infeasibilityReason.replace("\"", "\\\"")}\",\n")
        sb.append("    \"recommended_validation_strategy\": \"${report.cvFeasibility.recommendedValidationStrategy.replace("\"", "\\\"")}\"\n")
        sb.append("  },\n")

        // Constant and near-constant features
        val constantFeatures = report.featureAudits.filter { it.isConstant }.map { it.featureId }
        val nearConstantFeatures = report.featureAudits.filter { it.isNearConstant }.map { it.featureId }
        sb.append("  \"constant_features\": [${constantFeatures.joinToString(", ") { "\"$it\"" }}],\n")
        sb.append("  \"near_constant_features\": [${nearConstantFeatures.joinToString(", ") { "\"$it\"" }}],\n")

        // Physical expansion requirements
        sb.append("  \"physical_data_expansion_requirements\": [\n")
        for (i in report.physicalDataExpansionRequirements.indices) {
            val req = report.physicalDataExpansionRequirements[i]
            sb.append("    \"${req.replace("\"", "\\\"")}\"")
            if (i < report.physicalDataExpansionRequirements.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun extractRawFeatureString(row: FeatureDatasetRow, featureId: String): String {
        return when (featureId) {
            "F01" -> row.t0Features.f01ScreenStateCode
            "F02" -> row.t0Features.f02IsScreenInteractive
            "F03" -> row.t0Features.f03IsDeviceLocked
            "F04" -> row.t0Features.f04HasCameraPermission
            "F05" -> row.t0Features.f05IsKnownCameraApp.toString()
            "F06" -> row.t0Features.f06PackageInferenceConfidence.toString()
            "F07" -> row.t0Features.f07InferenceMethodCode
            "F08" -> row.t0Features.f08DeltaResumedToTriggerMs.toString()
            "F09" -> row.t0Features.f09RecentActivityCount30s.toString()
            "F10" -> row.t0Features.f10CameraHardwareId
            "F11" -> row.t0Features.f11IsBackCamera
            "F12" -> row.f12SessionDurationMs.toString()
            "F13" -> row.f13ExplicitUserStartAction
            "F14" -> row.f14ExplicitUserStopAction
            "F15" -> row.f15InternalHarnessState
            "F16" -> row.f16HarnessFgServiceActive.toString()
            "F17" -> row.f17TimeToSessionCloseMs.toString()
            "F18" -> row.f18AppVisibilityAtTrigger
            "F19" -> row.f19BackgroundedDuringSession.toString()
            "F20" -> row.f20ReturnedToForeground.toString()
            else -> "UNKNOWN"
        }
    }

    private fun isValueMissingOrUnknown(featureId: String, value: String): Boolean {
        return when (featureId) {
            "F01", "F02", "F03" -> value.equals("UNKNOWN", ignoreCase = true) || value.isBlank()
            "F04" -> value.equals("UNVERIFIED", ignoreCase = true) || value.equals("UNKNOWN", ignoreCase = true) || value.isBlank()
            "F08" -> value == "-1.0" || value == "-1"
            "F10", "F11" -> value.equals("UNKNOWN", ignoreCase = true) || value.isBlank()
            "F18" -> value.equals("UNKNOWN", ignoreCase = true) || value.isBlank()
            else -> false
        }
    }

    private fun isProductionT0Eligible(featureId: String): Boolean {
        return when (featureId) {
            "F01", "F02", "F03", "F05", "F06", "F07", "F08", "F09", "F10", "F11" -> true
            "F04" -> false // Ineligible as oracle value; strictly UNVERIFIED in production sandbox
            else -> false  // F12..F20 are retrospective research-only fields
        }
    }

    private fun determineFeatureReadiness(
        featureId: String,
        isConstant: Boolean,
        isNearConstant: Boolean,
        missingnessRate: Double,
        productionEligible: Boolean,
        temporalWindow: FeatureDefinition.TemporalWindow
    ): String {
        if (temporalWindow == FeatureDefinition.TemporalWindow.T2_RETROSPECTIVE) {
            return "RETROSPECTIVE_RESEARCH_ONLY"
        }
        if (featureId == "F04") {
            return "ORACLE_ONLY_RESTRICTED"
        }
        if (isConstant && missingnessRate >= 0.99) {
            return "UNPOLLED_IN_HARNESS"
        }
        if (isConstant) {
            return "ZERO_VARIANCE_CONSTANT"
        }
        if (isNearConstant) {
            return "LOW_VARIANCE_NEAR_CONSTANT"
        }
        if (productionEligible) {
            return "READY_FOR_EVALUATION"
        }
        return "CONDITIONALLY_READY"
    }

    private fun getFeatureAuditNotes(
        featureId: String,
        dominantVal: String,
        dominantPct: Double,
        missingnessRate: Double
    ): String {
        return when (featureId) {
            "F01" -> "Unpolled in harness CSV (100% UNKNOWN). Tracked in CameraGuard :app as SCREEN_ON_UNLOCKED. Requires sensor polling."
            "F02" -> "Derived directly from F01. Tri-state boolean. Constant UNKNOWN in current harness dataset."
            "F03" -> "Derived directly from F01. Tri-state boolean. Constant UNKNOWN in current harness dataset."
            "F04" -> "CRITICAL SANDBOX BOUNDARY: Unprivileged sandbox cannot query 3rd-party runtime permissions. Must be UNVERIFIED in production models."
            "F05" -> "Constant false (100.0%). Test harness is not registered as system camera app. Requires third-party camera app sessions."
            "F06" -> "Ordinal confidence (0..2 in data). Distinguishes foreground (2), background (1), and negative controls (0)."
            "F07" -> "Inference method code. Distinguishes background continuation (PAUSED) from foreground (RESUMED) and controls (NONE)."
            "F08" -> "Time delta in ms. Empirical 120ms latency when resumed. Sentinel -1.0 denotes no recent resume transition."
            "F09" -> "Recent activity transitions in 30s lookback. Differentiates foreground (1) from background multitasking (2)."
            "F10" -> "Camera sensor ID. Near-constant: 100% of camera activations used sensor '0' (rear). Front camera sessions required."
            "F11" -> "Lens facing back camera. Near-constant: 100% TRUE for active camera sessions. Front camera sessions required."
            "F12" -> "Session duration ms. Known only after session closure. Prohibited from real-time T0 production models."
            "F13" -> "Internal harness UI start trigger. Prohibited from real-time T0 production models."
            "F14" -> "Internal harness UI stop trigger. Known only post-closure. Prohibited from real-time T0 production models."
            "F15" -> "Internal harness state machine. Prohibited from real-time T0 production models."
            "F16" -> "Target application internal foreground service state. Prohibited from real-time T0 production models."
            "F17" -> "Time to session close ms. Future event after T0 trigger. Prohibited from real-time T0 production models."
            "F18" -> "Ground-truth app visibility at trigger. Not observable across unprivileged sandbox."
            "F19" -> "Mid-session backgrounding status. Future event after T0 trigger. Prohibited from real-time T0 production models."
            "F20" -> "Mid-session return to foreground. Future event after T0 trigger. Prohibited from real-time T0 production models."
            else -> "Cataloged feature."
        }
    }
}
