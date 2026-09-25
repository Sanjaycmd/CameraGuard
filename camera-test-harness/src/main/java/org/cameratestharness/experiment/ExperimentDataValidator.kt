package org.cameratestharness.experiment

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)

object ExperimentDataValidator {

    private val validScenarioIds = ExperimentScenario.entries.map { it.id }.toSet()
    private val validGroundTruthLabels = GroundTruthContext.entries.map { it.label }.toSet()
    private val expectedHeaders = ExperimentRecord.CSV_HEADER.split(",")

    fun validateRecords(records: List<ExperimentRecord>): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (records.isEmpty()) {
            warnings.add("Record list is empty")
            return ValidationResult(isValid = true, errors = errors, warnings = warnings)
        }

        for ((index, record) in records.withIndex()) {
            val rowNum = index + 1

            // 1. Session / Sample ID validation
            if (record.sampleId.isBlank()) {
                errors.add("Row $rowNum: sample_id is blank")
            }

            // 2. Timestamp validation
            if (record.timestamp.isBlank()) {
                errors.add("Row $rowNum: timestamp is blank")
            }

            // 3. Scenario ID validation
            if (record.scenarioId !in validScenarioIds) {
                errors.add("Row $rowNum: invalid scenario_id '${record.scenarioId}'")
            }

            // 4. Ground Truth Context validation
            if (record.groundTruthContext !in validGroundTruthLabels) {
                errors.add("Row $rowNum: invalid ground_truth_context '${record.groundTruthContext}'")
            }

            // 5. Package Name validation
            if (record.packageName != "org.cameratestharness") {
                warnings.add("Row $rowNum: unexpected package_name '${record.packageName}'")
            }

            // 6. Session Duration validation
            if (record.sessionDurationMs != ExperimentRecord.VALUE_UNKNOWN &&
                record.sessionDurationMs != ExperimentRecord.VALUE_UNAVAILABLE
            ) {
                val duration = record.sessionDurationMs.toLongOrNull()
                if (duration == null || duration < 0) {
                    errors.add("Row $rowNum: invalid session_duration_ms '${record.sessionDurationMs}'")
                }
            }
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    fun validateCsv(csvContent: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val records = try {
            ExperimentDataExporter.parseCsv(csvContent)
        } catch (e: Exception) {
            errors.add("CSV parsing error: ${e.message}")
            return ValidationResult(isValid = false, errors = errors, warnings = warnings)
        }

        val recordValidation = validateRecords(records)
        errors.addAll(recordValidation.errors)
        warnings.addAll(recordValidation.warnings)

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
}
