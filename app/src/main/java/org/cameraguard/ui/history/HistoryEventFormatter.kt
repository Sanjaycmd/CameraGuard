package org.cameraguard.ui.history

import org.cameraguard.data.model.CameraAccessEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HistoryEventFormatter {

    private fun getDefaultDateFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * Formats a single [CameraAccessEvent] into a detailed, human-readable plain text string.
     */
    fun formatEvent(event: CameraAccessEvent, dateFormat: SimpleDateFormat = getDefaultDateFormat()): String {
        val permLabel = when (event.candidateHasCameraPermission) {
            true -> "GRANTED"
            false -> "NOT GRANTED"
            null -> "UNVERIFIED"
        }
        val formattedTime = dateFormat.format(Date(event.timestamp))

        val builder = StringBuilder()
        builder.appendLine("Classification: ${event.classification.name}")
        if (event.isSynthetic) {
            builder.appendLine("Synthetic: YES")
        }
        builder.appendLine("Timestamp: $formattedTime")
        builder.appendLine("Raw Event: ${event.rawEventType.name}")
        builder.appendLine("Camera ID: ${event.cameraId ?: "N/A"}")
        builder.appendLine("Inferred App: ${event.inferredPackageName ?: "None / Uncorrelated"}")
        builder.appendLine("Confidence: ${event.packageInferenceConfidence.name}")
        builder.appendLine("Inference Method: ${event.inferenceMethod.name}")
        builder.appendLine("Screen State: ${event.screenState.name}")
        builder.appendLine("Camera Permission: $permLabel")
        if (event.classificationExplanation.isNotBlank()) {
            builder.appendLine("Rationale: ${event.classificationExplanation}")
        }
        event.detectionLatencyMs?.let {
            builder.appendLine("Evaluation Latency: ${it}ms")
        }
        return builder.toString().trimEnd()
    }

    /**
     * Formats a list of [CameraAccessEvent]s into a complete history plain text string.
     * Events are separated by dividers.
     * If the list is empty, returns "CameraGuard History: No events recorded."
     */
    fun formatHistory(events: List<CameraAccessEvent>, dateFormat: SimpleDateFormat = getDefaultDateFormat()): String {
        if (events.isEmpty()) {
            return "CameraGuard History: No events recorded."
        }
        val countStr = "${events.size} event${if (events.size == 1) "" else "s"}"
        val header = "=== CameraGuard Event History ($countStr) ===\n\n"
        val body = events.joinToString(separator = "\n\n----------------------------------------\n\n") {
            formatEvent(it, dateFormat)
        }
        return header + body
    }
}
