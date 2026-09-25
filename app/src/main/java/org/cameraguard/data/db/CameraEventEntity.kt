package org.cameraguard.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.CameraAccessEvent
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.data.model.ScreenInteractivityState

@Entity(tableName = "camera_events")
data class CameraEventEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long,
    val rawEventType: String,
    val cameraId: String?,
    val screenState: String,
    val inferredPackageName: String?,
    val packageInferenceConfidence: String,
    val inferenceMethod: String,
    val candidateHasCameraPermission: Boolean?,
    val classification: String,
    val classificationExplanation: String,
    val detectionLatencyMs: Long?,
    val isSynthetic: Boolean,
    val tierUsed: String? = null,
    val deterministicResult: String? = null,
    val mlResult: String? = null,
    val mlInvoked: Boolean = false
) {
    fun toDomain(): CameraAccessEvent {
        return CameraAccessEvent(
            id = id,
            timestamp = timestamp,
            rawEventType = runCatching { RawCameraEventType.valueOf(rawEventType) }
                .getOrDefault(RawCameraEventType.CAMERA_BECAME_UNAVAILABLE),
            cameraId = cameraId,
            screenState = runCatching { ScreenInteractivityState.valueOf(screenState) }
                .getOrDefault(ScreenInteractivityState.UNKNOWN),
            inferredPackageName = inferredPackageName,
            packageInferenceConfidence = runCatching { InferenceConfidence.valueOf(packageInferenceConfidence) }
                .getOrDefault(InferenceConfidence.NONE),
            inferenceMethod = runCatching { InferenceMethod.valueOf(inferenceMethod) }
                .getOrDefault(InferenceMethod.NONE),
            candidateHasCameraPermission = candidateHasCameraPermission,
            classification = runCatching { AccessClassification.valueOf(classification) }
                .getOrDefault(AccessClassification.UNKNOWN),
            classificationExplanation = classificationExplanation,
            detectionLatencyMs = detectionLatencyMs,
            isSynthetic = isSynthetic,
            tierUsed = tierUsed,
            deterministicResult = deterministicResult,
            mlResult = mlResult,
            mlInvoked = mlInvoked
        )
    }

    companion object {
        fun fromDomain(event: CameraAccessEvent): CameraEventEntity {
            return CameraEventEntity(
                id = event.id,
                timestamp = event.timestamp,
                rawEventType = event.rawEventType.name,
                cameraId = event.cameraId,
                screenState = event.screenState.name,
                inferredPackageName = event.inferredPackageName,
                packageInferenceConfidence = event.packageInferenceConfidence.name,
                inferenceMethod = event.inferenceMethod.name,
                candidateHasCameraPermission = event.candidateHasCameraPermission,
                classification = event.classification.name,
                classificationExplanation = event.classificationExplanation,
                detectionLatencyMs = event.detectionLatencyMs,
                isSynthetic = event.isSynthetic,
                tierUsed = event.tierUsed,
                deterministicResult = event.deterministicResult,
                mlResult = event.mlResult,
                mlInvoked = event.mlInvoked
            )
        }
    }
}
