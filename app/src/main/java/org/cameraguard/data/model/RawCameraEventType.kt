package org.cameraguard.data.model

/**
 * Raw hardware camera availability events as directly observed from
 * Android's [android.hardware.camera2.CameraManager.AvailabilityCallback].
 *
 * NOTE: As per research methodology, onCameraUnavailable is NOT represented
 * as definitive proof that the camera opened, but as an observable hardware
 * availability transition.
 */
enum class RawCameraEventType {
    CAMERA_BECAME_UNAVAILABLE,
    CAMERA_BECAME_AVAILABLE,
    TEST_SYNTHETIC_EVENT
}
