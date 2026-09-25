package org.cameratestharness

enum class HarnessState {
    IDLE,
    ARMED,
    STARTING,
    CAMERA_OPENING,
    CAMERA_OPEN,
    CAPTURE_SESSION_ACTIVE,
    RUNNING,
    APP_BACKGROUND,
    STOPPING,
    ERROR
}
