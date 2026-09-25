package org.cameratestharness.experiment

enum class GroundTruthContext(val label: String) {
    USER_INITIATED_FOREGROUND("USER_INITIATED_FOREGROUND"),
    USER_INITIATED_BACKGROUND_CONTINUATION("USER_INITIATED_BACKGROUND_CONTINUATION"),
    CAMERA_SESSION_CLOSED("CAMERA_SESSION_CLOSED"),
    PERMISSION_DENIED("PERMISSION_DENIED"),
    NO_CAMERA_ACTIVITY("NO_CAMERA_ACTIVITY"),
    AMBIGUOUS_CONTEXT("AMBIGUOUS_CONTEXT");

    companion object {
        fun fromLabel(label: String): GroundTruthContext? = entries.find { it.label == label }
    }
}
