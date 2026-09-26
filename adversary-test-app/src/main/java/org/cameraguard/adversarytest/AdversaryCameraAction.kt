package org.cameraguard.adversarytest

enum class AdversaryScenario(val id: String, val description: String) {
    A1_FOREGROUND("A1", "Foreground intentional control"),
    A2_BACKGROUND("A2", "Background camera-access attempt"),
    A3_POST_FOREGROUND("A3", "Access immediately after leaving foreground"),
    A4_SERVICE("A4", "Service/background component attempt"),
    A5_SCREEN_LOCKED("A5", "Screen-locked attempt"),
    A6_RAPID("A6", "Rapid suspicious start/stop burst");

    companion object {
        fun fromId(id: String): AdversaryScenario? = entries.find { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) }
    }
}

enum class AccessGroundTruth {
    SUCCESS,
    DENIED,
    BLOCKED_BY_PLATFORM,
    FAILED,
    UNKNOWN
}

enum class PrivacyIndicatorObservation {
    VISIBLE,
    NOT_OBSERVED,
    NOT_APPLICABLE,
    UNKNOWN
}
