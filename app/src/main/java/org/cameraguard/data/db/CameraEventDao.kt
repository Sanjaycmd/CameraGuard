package org.cameraguard.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: CameraEventEntity): Long

    @Query("SELECT * FROM camera_events ORDER BY timestamp DESC")
    fun getAllEventsFlow(): Flow<List<CameraEventEntity>>

    @Query("SELECT * FROM camera_events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEventsFlow(limit: Int): Flow<List<CameraEventEntity>>

    @Query("SELECT COUNT(*) FROM camera_events")
    fun getTotalEventCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM camera_events WHERE classification = 'UNEXPECTED'")
    fun getUnexpectedCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM camera_events WHERE classification = 'EXPECTED'")
    fun getExpectedCountFlow(): Flow<Int>

    @Query("SELECT * FROM camera_events ORDER BY timestamp DESC LIMIT 1")
    fun getLatestEventFlow(): Flow<CameraEventEntity?>

    @Query("SELECT * FROM camera_events WHERE id = :id LIMIT 1")
    suspend fun getEventById(id: String): CameraEventEntity?

    @Query("DELETE FROM camera_events")
    suspend fun clearAll(): Int

    @Query("DELETE FROM camera_events WHERE isSynthetic = 1")
    suspend fun clearSyntheticEvents(): Int
}
