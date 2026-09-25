package org.cameratestharness.experiment

data class ExperimentRecord(
    val sampleId: String,
    val timestamp: String,
    val scenarioId: String,
    val groundTruthContext: String,
    val userAction: String,
    val cameraEvent: String,
    val cameraId: String,
    val packageName: String = "org.cameratestharness",
    val cameraPermission: String,
    val activityState: String,
    val appVisibility: String,
    val foregroundServiceActive: Boolean,
    val foregroundServiceType: String,
    val screenState: String,
    val sessionState: String,
    val sessionDurationMs: String,
    val recentUserInteraction: Boolean,
    val lifecycleEvent: String,
    val cameraAvailability: String,
    val notes: String = ""
) {
    companion object {
        const val CSV_HEADER = "sample_id,timestamp,scenario_id,ground_truth_context,user_action,camera_event,camera_id,package_name,camera_permission,activity_state,app_visibility,foreground_service_active,foreground_service_type,screen_state,session_state,session_duration_ms,recent_user_interaction,lifecycle_event,camera_availability,notes"
        const val VALUE_UNKNOWN = "UNKNOWN"
        const val VALUE_UNAVAILABLE = "UNAVAILABLE"
        const val VALUE_NONE = "NONE"
    }

    fun toCsvRow(): String {
        return listOf(
            escapeCsv(sampleId),
            escapeCsv(timestamp),
            escapeCsv(scenarioId),
            escapeCsv(groundTruthContext),
            escapeCsv(userAction),
            escapeCsv(cameraEvent),
            escapeCsv(cameraId),
            escapeCsv(packageName),
            escapeCsv(cameraPermission),
            escapeCsv(activityState),
            escapeCsv(appVisibility),
            foregroundServiceActive.toString(),
            escapeCsv(foregroundServiceType),
            escapeCsv(screenState),
            escapeCsv(sessionState),
            escapeCsv(sessionDurationMs),
            recentUserInteraction.toString(),
            escapeCsv(lifecycleEvent),
            escapeCsv(cameraAvailability),
            escapeCsv(notes)
        ).joinToString(",")
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
