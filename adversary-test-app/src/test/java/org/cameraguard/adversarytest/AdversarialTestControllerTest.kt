package org.cameraguard.adversarytest

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdversarialTestControllerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var controller: AdversarialTestController

    @Before
    fun setUp() {
        controller = AdversarialTestController(testScope)
    }

    @Test
    fun testInitialState_isClosed_withDefault10SecondsDuration() {
        assertEquals(AdversarialTestController.TestState.CLOSED, controller.currentState)
        assertEquals(10, controller.selectedDurationSeconds)
        assertEquals(0, controller.elapsedSeconds)
        assertNull(controller.activeCameraId)
    }

    @Test
    fun testSelectDuration_validatesAllowedOptions() {
        controller.selectDuration(5)
        assertEquals(5, controller.selectedDurationSeconds)

        controller.selectDuration(15)
        assertEquals(15, controller.selectedDurationSeconds)

        controller.selectDuration(30)
        assertEquals(30, controller.selectedDurationSeconds)

        // Invalid duration should be rejected, preserving previous value
        controller.selectDuration(45)
        assertEquals(30, controller.selectedDurationSeconds)
    }

    @Test
    fun testResolveTargetCameraId_prefersRearFacingCamera() {
        val cameras = listOf(
            CameraInfoDescriptor(id = "1", isRearFacing = false), // Front
            CameraInfoDescriptor(id = "0", isRearFacing = true),  // Rear
            CameraInfoDescriptor(id = "2", isRearFacing = false)  // External
        )
        val selected = controller.resolveTargetCameraId(cameras)
        assertEquals("0", selected)
        assertEquals("0", controller.activeCameraId)
    }

    @Test
    fun testResolveTargetCameraId_emptyList_returnsNull() {
        val selected = controller.resolveTargetCameraId(emptyList())
        assertNull(selected)
        assertNull(controller.activeCameraId)
    }

    @Test
    fun testStartTest_noCameraAvailable_transitionsToNoCameraState() {
        controller.startTest(cameraId = null) { _, _, _ -> }
        assertEquals(AdversarialTestController.TestState.NO_CAMERA_AVAILABLE, controller.currentState)
    }

    @Test
    fun testStartTest_successfulOpen_transitionsToActive_andTicksCountdown() = runTest(testDispatcher) {
        var cameraOpenedCalled = false
        controller.startTest("0") { _, onOpened, _ ->
            cameraOpenedCalled = true
            onOpened()
        }

        assertTrue(cameraOpenedCalled)
        assertEquals(AdversarialTestController.TestState.ACTIVE, controller.currentState)
        assertEquals("0", controller.activeCameraId)

        // Advance 4 seconds
        testDispatcher.scheduler.advanceTimeBy(4000L)
        testDispatcher.scheduler.runCurrent()
        assertEquals(4, controller.elapsedSeconds)
        assertEquals(AdversarialTestController.TestState.ACTIVE, controller.currentState)

        // Advance remaining 6 seconds to complete 10s test
        testDispatcher.scheduler.advanceTimeBy(6000L)
        testDispatcher.scheduler.runCurrent()
        assertEquals(10, controller.elapsedSeconds)
        assertEquals(AdversarialTestController.TestState.COMPLETE, controller.currentState)
    }

    @Test
    fun testStartTest_errorDuringOpen_transitionsToErrorState() {
        controller.startTest("0") { _, _, onError ->
            onError("Camera in use by another package")
        }

        assertEquals(AdversarialTestController.TestState.ERROR, controller.currentState)
        assertEquals("Camera in use by another package", controller.lastErrorMessage)
    }

    @Test
    fun testStopTest_manualInterruption_resetsToClosed() = runTest(testDispatcher) {
        var cameraClosed = false
        controller.startTest("0") { _, onOpened, _ -> onOpened() }
        assertEquals(AdversarialTestController.TestState.ACTIVE, controller.currentState)

        testDispatcher.scheduler.advanceTimeBy(3000L)
        testDispatcher.scheduler.runCurrent()
        assertEquals(3, controller.elapsedSeconds)

        controller.stopTest(isCompleted = false) {
            cameraClosed = true
        }

        assertTrue(cameraClosed)
        assertEquals(AdversarialTestController.TestState.CLOSED, controller.currentState)

        // Further time advance should not change elapsed time or trigger completion
        testDispatcher.scheduler.advanceTimeBy(10000L)
        testDispatcher.scheduler.runCurrent()
        assertEquals(3, controller.elapsedSeconds)
        assertEquals(AdversarialTestController.TestState.CLOSED, controller.currentState)
    }

    @Test
    fun testRepeatedStartAndStop_cyclesCleanly() = runTest(testDispatcher) {
        // Cycle 1
        controller.startTest("0") { _, onOpened, _ -> onOpened() }
        assertEquals(AdversarialTestController.TestState.ACTIVE, controller.currentState)
        controller.stopTest(isCompleted = false)
        assertEquals(AdversarialTestController.TestState.CLOSED, controller.currentState)

        // Cycle 2
        controller.startTest("0") { _, onOpened, _ -> onOpened() }
        assertEquals(AdversarialTestController.TestState.ACTIVE, controller.currentState)
        testDispatcher.scheduler.advanceTimeBy(10000L)
        testDispatcher.scheduler.runCurrent()
        assertEquals(AdversarialTestController.TestState.COMPLETE, controller.currentState)

        // Cycle 3
        controller.startTest("0") { _, onOpened, _ -> onOpened() }
        assertEquals(AdversarialTestController.TestState.ACTIVE, controller.currentState)
        controller.stopTest(isCompleted = false)
        assertEquals(AdversarialTestController.TestState.CLOSED, controller.currentState)
    }

    @Test
    fun testCleanup_cancelsOngoingJobs_andSetsClosed() = runTest(testDispatcher) {
        var closedCallback = false
        controller.startTest("0") { _, onOpened, _ -> onOpened() }

        controller.cleanup {
            closedCallback = true
        }

        assertTrue(closedCallback)
        assertEquals(AdversarialTestController.TestState.CLOSED, controller.currentState)
    }

    @Test
    fun testOnCloseCameraCallback_triggeredOnNaturalCompletionAndStop() = runTest(testDispatcher) {
        var cameraCloseCount = 0
        controller.onCloseCamera = {
            cameraCloseCount++
        }

        // Test 1: natural completion
        controller.selectDuration(5)
        controller.startTest("0") { _, onOpened, _ -> onOpened() }
        testDispatcher.scheduler.advanceTimeBy(5000L)
        testDispatcher.scheduler.runCurrent()
        assertEquals(AdversarialTestController.TestState.COMPLETE, controller.currentState)
        assertEquals(1, cameraCloseCount)

        // Test 2: manual stop
        controller.startTest("0") { _, onOpened, _ -> onOpened() }
        controller.stopTest(isCompleted = false)
        assertEquals(2, cameraCloseCount)

        // Test 3: lifecycle cleanup
        controller.startTest("0") { _, onOpened, _ -> onOpened() }
        controller.cleanup()
        assertEquals(3, cameraCloseCount)
    }

    @Test
    fun testStartA2Test_noCameraAvailable_transitionsToNoCameraState() {
        controller.startA2Test(cameraId = null) { _, _ -> }
        assertEquals(AdversarialTestController.TestState.NO_CAMERA_AVAILABLE, controller.currentState)
    }

    @Test
    fun testStartA2Test_blockedByPlatform_transitionsToA2Blocked_andRecordsResult() {
        var a2Executed = false
        var completedCallbackCalled = false

        controller.onA2Completed = {
            completedCallbackCalled = true
        }

        controller.startA2Test("0") { camId, onComplete ->
            assertEquals("0", camId)
            a2Executed = true
            assertEquals(AdversarialTestController.TestState.A2_RUNNING, controller.currentState)

            onComplete(
                A2ExecutionResult(
                    attempted = true,
                    backgrounded = true,
                    foregroundServiceActive = false,
                    platformResult = "BLOCKED_BY_PLATFORM (ERROR_CAMERA_DISABLED / error 3)",
                    hardwareAcquired = false,
                    framesCaptured = 0,
                    detail = "Platform security policy denied background camera access"
                )
            )
        }

        assertTrue(a2Executed)
        assertTrue(completedCallbackCalled)
        assertEquals(AdversarialTestController.TestState.A2_BLOCKED, controller.currentState)
        val result = controller.lastA2Result
        assertTrue(result != null)
        assertTrue(result!!.attempted)
        assertTrue(result.backgrounded)
        assertFalse(result.foregroundServiceActive)
        assertEquals("BLOCKED_BY_PLATFORM (ERROR_CAMERA_DISABLED / error 3)", result.platformResult)
        assertFalse(result.hardwareAcquired)
        assertEquals(0, result.framesCaptured)
    }

    @Test
    fun testStartA2Test_unexpectedlyGranted_transitionsToComplete() {
        controller.startA2Test("0") { _, onComplete ->
            onComplete(
                A2ExecutionResult(
                    attempted = true,
                    backgrounded = true,
                    foregroundServiceActive = false,
                    platformResult = "GRANTED_UNEXPECTEDLY",
                    hardwareAcquired = true,
                    framesCaptured = 0,
                    detail = "Unexpectedly granted"
                )
            )
        }

        assertEquals(AdversarialTestController.TestState.COMPLETE, controller.currentState)
        assertTrue(controller.lastA2Result!!.hardwareAcquired)
    }

    @Test
    fun testStartA2Test_ignoredWhenActive() {
        controller.startTest("0") { _, onOpened, _ -> onOpened() }
        assertEquals(AdversarialTestController.TestState.ACTIVE, controller.currentState)

        var a2Called = false
        controller.startA2Test("0") { _, _ -> a2Called = true }
        assertFalse(a2Called)
        assertEquals(AdversarialTestController.TestState.ACTIVE, controller.currentState)
    }

    @Test
    fun testStartTest_ignoredWhenA2Running() {
        controller.startA2Test("0") { _, _ ->
            // In the middle of A2, do not complete immediately
        }
        assertEquals(AdversarialTestController.TestState.A2_RUNNING, controller.currentState)

        var openCalled = false
        controller.startTest("0") { _, onOpened, _ ->
            openCalled = true
            onOpened()
        }
        assertFalse(openCalled)
        assertEquals(AdversarialTestController.TestState.A2_RUNNING, controller.currentState)
    }

    @Test
    fun testSelectDuration_ignoredWhenA2Running() {
        controller.selectDuration(10)
        controller.startA2Test("0") { _, _ -> }
        assertEquals(AdversarialTestController.TestState.A2_RUNNING, controller.currentState)

        controller.selectDuration(30)
        assertEquals(10, controller.selectedDurationSeconds)
    }
}
