package org.cameratestharness

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Camera lens facing options for controlled experiment selection.
 */
enum class CameraLensOption(val id: String, val displayName: String) {
    AUTO_DEFAULT("AUTO", "Auto (Default Rear)"),
    BACK("BACK", "Rear / Back Camera"),
    FRONT("FRONT", "Front / Selfie Camera");

    companion object {
        fun fromId(id: String?): CameraLensOption {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO_DEFAULT
        }
    }
}

/**
 * Decoupled camera device metadata for testing and hardware enumeration.
 */
data class CameraDeviceInfo(
    val cameraId: String,
    val lensFacing: Int // Matches CameraCharacteristics.LENS_FACING_*
) {
    val lensFacingName: String
        get() = when (lensFacing) {
            CameraSelector.LENS_FACING_FRONT -> "FRONT"
            CameraSelector.LENS_FACING_BACK -> "BACK"
            CameraSelector.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
}

/**
 * Result of camera selection logic.
 */
data class CameraSelectionResult(
    val selectedId: String?,
    val selectedLensFacing: Int?,
    val isFallback: Boolean,
    val statusMessage: String
) {
    val isFrontCamera: Boolean
        get() = selectedLensFacing == CameraSelector.LENS_FACING_FRONT

    val isBackCamera: Boolean
        get() = selectedLensFacing == CameraSelector.LENS_FACING_BACK
}

/**
 * Controlled camera selection engine for CameraGuard experiment test harness.
 * Evaluates hardware camera devices via CameraCharacteristics.LENS_FACING,
 * supporting AUTO, BACK, and FRONT camera selections without assuming "0" or "1".
 */
object CameraSelector {

    private const val TAG = "CameraSelector"

    // Standard Android CameraCharacteristics.LENS_FACING constant equivalents
    const val LENS_FACING_FRONT = 0
    const val LENS_FACING_BACK = 1
    const val LENS_FACING_EXTERNAL = 2

    /**
     * Inspects physical camera hardware using CameraManager and returns device metadata list.
     */
    fun inspectAvailableCameras(cameraManager: CameraManager): List<CameraDeviceInfo> {
        val devices = mutableListOf<CameraDeviceInfo>()
        try {
            val idList = cameraManager.cameraIdList
            for (id in idList) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: LENS_FACING_BACK
                    devices.add(CameraDeviceInfo(cameraId = id, lensFacing = facing))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read characteristics for camera ID $id", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate camera IDs", e)
        }
        return devices
    }

    /**
     * Selects appropriate camera device based on desired lens option.
     * Guaranteed pure logic: directly unit testable without physical device.
     */
    fun selectCamera(
        availableCameras: List<CameraDeviceInfo>,
        desiredOption: CameraLensOption = CameraLensOption.AUTO_DEFAULT
    ): CameraSelectionResult {
        if (availableCameras.isEmpty()) {
            return CameraSelectionResult(
                selectedId = null,
                selectedLensFacing = null,
                isFallback = false,
                statusMessage = "No camera devices available on this hardware"
            )
        }

        return when (desiredOption) {
            CameraLensOption.FRONT -> {
                val frontCamera = availableCameras.firstOrNull { it.lensFacing == LENS_FACING_FRONT }
                if (frontCamera != null) {
                    CameraSelectionResult(
                        selectedId = frontCamera.cameraId,
                        selectedLensFacing = frontCamera.lensFacing,
                        isFallback = false,
                        statusMessage = "Selected front camera: ID ${frontCamera.cameraId} (LENS_FACING_FRONT)"
                    )
                } else {
                    // Graceful fallback to default/rear
                    val fallback = availableCameras.firstOrNull { it.lensFacing == LENS_FACING_BACK } ?: availableCameras.first()
                    CameraSelectionResult(
                        selectedId = fallback.cameraId,
                        selectedLensFacing = fallback.lensFacing,
                        isFallback = true,
                        statusMessage = "No front camera found; fell back to camera ID ${fallback.cameraId} (${fallback.lensFacingName})"
                    )
                }
            }
            CameraLensOption.BACK -> {
                val backCamera = availableCameras.firstOrNull { it.lensFacing == LENS_FACING_BACK }
                if (backCamera != null) {
                    CameraSelectionResult(
                        selectedId = backCamera.cameraId,
                        selectedLensFacing = backCamera.lensFacing,
                        isFallback = false,
                        statusMessage = "Selected rear camera: ID ${backCamera.cameraId} (LENS_FACING_BACK)"
                    )
                } else {
                    val fallback = availableCameras.first()
                    CameraSelectionResult(
                        selectedId = fallback.cameraId,
                        selectedLensFacing = fallback.lensFacing,
                        isFallback = true,
                        statusMessage = "No rear camera found; fell back to camera ID ${fallback.cameraId} (${fallback.lensFacingName})"
                    )
                }
            }
            CameraLensOption.AUTO_DEFAULT -> {
                val defaultCamera = availableCameras.firstOrNull { it.lensFacing == LENS_FACING_BACK } ?: availableCameras.first()
                CameraSelectionResult(
                    selectedId = defaultCamera.cameraId,
                    selectedLensFacing = defaultCamera.lensFacing,
                    isFallback = false,
                    statusMessage = "Selected default camera: ID ${defaultCamera.cameraId} (${defaultCamera.lensFacingName})"
                )
            }
        }
    }
}
