package org.cameraguard.ui.main

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.CameraAccessEvent
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import org.cameraguard.data.model.RawCameraEventType
import org.cameraguard.data.model.ScreenInteractivityState
import org.cameraguard.data.repository.CameraEventRepository
import org.cameraguard.monitoring.CameraMonitor
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID

class MainScreenViewModelTest {

    private lateinit var fakeRepository: FakeCameraEventRepository
    private lateinit var monitor: CameraMonitor

    @Before
    fun setup() {
        fakeRepository = FakeCameraEventRepository()
        monitor = CameraMonitor(fakeRepository)
    }

    @Test
    fun testRepository_recordsAndCountsEvents() = runTest {
        assertEquals(0, fakeRepository.getTotalEventCount().first())

        val event = CameraAccessEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            screenState = ScreenInteractivityState.SCREEN_OFF,
            classification = AccessClassification.UNEXPECTED,
            isSynthetic = true
        )

        fakeRepository.recordEvent(event)

        assertEquals(1, fakeRepository.getTotalEventCount().first())
        assertEquals(1, fakeRepository.getUnexpectedCount().first())
        assertEquals(0, fakeRepository.getExpectedCount().first())
    }

    @Test
    fun testRepository_clearSyntheticEvents() = runTest {
        val realEvent = CameraAccessEvent(
            id = UUID.randomUUID().toString(),
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            classification = AccessClassification.EXPECTED,
            isSynthetic = false
        )
        val syntheticEvent = CameraAccessEvent(
            id = UUID.randomUUID().toString(),
            rawEventType = RawCameraEventType.CAMERA_BECAME_UNAVAILABLE,
            classification = AccessClassification.UNEXPECTED,
            isSynthetic = true
        )

        fakeRepository.recordEvent(realEvent)
        fakeRepository.recordEvent(syntheticEvent)
        assertEquals(2, fakeRepository.getTotalEventCount().first())

        fakeRepository.clearSyntheticEvents()
        assertEquals(1, fakeRepository.getTotalEventCount().first())
        assertEquals(false, fakeRepository.getAllEvents().first().first().isSynthetic)
    }
}

private class FakeCameraEventRepository : CameraEventRepository {
    private val events = MutableStateFlow<List<CameraAccessEvent>>(emptyList())

    override fun getAllEvents(): Flow<List<CameraAccessEvent>> = events

    override fun getRecentEvents(limit: Int): Flow<List<CameraAccessEvent>> =
        events.map { it.take(limit) }

    override fun getTotalEventCount(): Flow<Int> = events.map { it.size }

    override fun getUnexpectedCount(): Flow<Int> =
        events.map { it.count { e -> e.classification == AccessClassification.UNEXPECTED } }

    override fun getExpectedCount(): Flow<Int> =
        events.map { it.count { e -> e.classification == AccessClassification.EXPECTED } }

    override fun getLatestEvent(): Flow<CameraAccessEvent?> =
        events.map { it.firstOrNull() }

    override suspend fun recordEvent(event: CameraAccessEvent): Long {
        events.value = listOf(event) + events.value
        return 1L
    }

    override suspend fun clearAllEvents() {
        events.value = emptyList()
    }

    override suspend fun clearSyntheticEvents() {
        events.value = events.value.filter { !it.isSynthetic }
    }
}
