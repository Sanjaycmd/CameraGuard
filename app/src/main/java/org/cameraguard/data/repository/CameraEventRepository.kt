package org.cameraguard.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.cameraguard.data.db.CameraEventDao
import org.cameraguard.data.db.CameraEventEntity
import org.cameraguard.data.model.CameraAccessEvent

interface CameraEventRepository {
    fun getAllEvents(): Flow<List<CameraAccessEvent>>
    fun getRecentEvents(limit: Int = 50): Flow<List<CameraAccessEvent>>
    fun getTotalEventCount(): Flow<Int>
    fun getUnexpectedCount(): Flow<Int>
    fun getExpectedCount(): Flow<Int>
    fun getLatestEvent(): Flow<CameraAccessEvent?>
    suspend fun recordEvent(event: CameraAccessEvent): Long
    suspend fun clearAllEvents()
    suspend fun clearSyntheticEvents()
}

class DefaultCameraEventRepository(
    private val dao: CameraEventDao
) : CameraEventRepository {

    override fun getAllEvents(): Flow<List<CameraAccessEvent>> {
        return dao.getAllEventsFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getRecentEvents(limit: Int): Flow<List<CameraAccessEvent>> {
        return dao.getRecentEventsFlow(limit).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getTotalEventCount(): Flow<Int> = dao.getTotalEventCountFlow()

    override fun getUnexpectedCount(): Flow<Int> = dao.getUnexpectedCountFlow()

    override fun getExpectedCount(): Flow<Int> = dao.getExpectedCountFlow()

    override fun getLatestEvent(): Flow<CameraAccessEvent?> {
        return dao.getLatestEventFlow().map { it?.toDomain() }
    }

    override suspend fun recordEvent(event: CameraAccessEvent): Long {
        return dao.insertEvent(CameraEventEntity.fromDomain(event))
    }

    override suspend fun clearAllEvents() {
        dao.clearAll()
    }

    override suspend fun clearSyntheticEvents() {
        dao.clearSyntheticEvents()
    }
}
