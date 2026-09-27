package org.cameraguard.adversarytest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages the state machine, duration countdown, and lifecycle of the
 * independent adversarial camera test.
 *
 * Designed to be testable in JVM unit tests as well as on real devices.
 */
class AdversarialTestController(
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())
) {

    enum class TestState {
        CLOSED,
        ACTIVE,
        COMPLETE,
        NO_CAMERA_AVAILABLE,
        ERROR
    }

    companion object {
        val VALID_DURATIONS_SECONDS = listOf(5, 10, 15, 30)
        const val DEFAULT_DURATION_SECONDS = 10
    }

    var currentState: TestState = TestState.CLOSED
        private set

    var selectedDurationSeconds: Int = DEFAULT_DURATION_SECONDS
        private set

    var elapsedSeconds: Int = 0
        private set

    var activeCameraId: String? = null
        private set

    var lastErrorMessage: String? = null
        private set

    private var tickerJob: Job? = null
    var onStateChanged: ((TestState) -> Unit)? = null
    var onTick: ((elapsedSec: Int, remainingSec: Int) -> Unit)? = null
    var onCloseCamera: (() -> Unit)? = null

    fun selectDuration(seconds: Int) {
        if (currentState == TestState.ACTIVE) return // Cannot change during active capture
        if (seconds in VALID_DURATIONS_SECONDS) {
            selectedDurationSeconds = seconds
        }
    }

    /**
     * Resolves the target rear camera ID from a given list of available camera descriptors.
     * Prefers back/rear camera facing.
     */
    fun resolveTargetCameraId(cameraList: List<CameraInfoDescriptor>): String? {
        val rear = cameraList.firstOrNull { it.isRearFacing }
        val target = rear ?: cameraList.firstOrNull()
        activeCameraId = target?.id
        return activeCameraId
    }

    /**
     * Initiates the camera test session.
     * @param cameraId The resolved physical camera ID to open.
     * @param openCameraAction Lambda executing the actual platform Camera2 open call.
     */
    fun startTest(
        cameraId: String?,
        openCameraAction: (cameraId: String, onOpened: () -> Unit, onError: (String) -> Unit) -> Unit
    ) {
        if (currentState == TestState.ACTIVE) return

        if (cameraId.isNullOrEmpty()) {
            transitionTo(TestState.NO_CAMERA_AVAILABLE)
            return
        }

        activeCameraId = cameraId
        elapsedSeconds = 0
        lastErrorMessage = null

        openCameraAction(cameraId, {
            // Camera opened successfully
            transitionTo(TestState.ACTIVE)
            startCountdown()
        }, { errorMsg ->
            lastErrorMessage = errorMsg
            transitionTo(TestState.ERROR)
        })
    }

    private fun startCountdown() {
        tickerJob?.cancel()
        tickerJob = coroutineScope.launch {
            while (isActive && elapsedSeconds < selectedDurationSeconds) {
                delay(1000L)
                elapsedSeconds++
                val remaining = (selectedDurationSeconds - elapsedSeconds).coerceAtLeast(0)
                onTick?.invoke(elapsedSeconds, remaining)
            }
            if (isActive && elapsedSeconds >= selectedDurationSeconds) {
                stopTest(isCompleted = true)
            }
        }
    }

    /**
     * Stops the active camera test and resets or sets to complete.
     */
    fun stopTest(isCompleted: Boolean = false, closeCameraAction: (() -> Unit)? = null) {
        tickerJob?.cancel()
        tickerJob = null
        closeCameraAction?.invoke()
        onCloseCamera?.invoke()

        val nextState = if (isCompleted) TestState.COMPLETE else TestState.CLOSED
        transitionTo(nextState)
    }

    /**
     * Complete lifecycle cleanup when activity stops or is destroyed.
     */
    fun cleanup(closeCameraAction: (() -> Unit)? = null) {
        tickerJob?.cancel()
        tickerJob = null
        closeCameraAction?.invoke()
        onCloseCamera?.invoke()
        transitionTo(TestState.CLOSED)
    }

    private fun transitionTo(newState: TestState) {
        currentState = newState
        onStateChanged?.invoke(newState)
    }
}

/**
 * Lightweight hardware camera descriptor decoupled from android.hardware.camera2
 * to facilitate unit testing without mock frameworks.
 */
data class CameraInfoDescriptor(
    val id: String,
    val isRearFacing: Boolean
)
