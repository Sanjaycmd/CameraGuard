package org.cameraguard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CameraEventEntity::class],
    version = 1,
    exportSchema = false
)
abstract class CameraGuardDatabase : RoomDatabase() {

    abstract fun cameraEventDao(): CameraEventDao

    companion object {
        @Volatile
        private var INSTANCE: CameraGuardDatabase? = null

        fun getInstance(context: Context): CameraGuardDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CameraGuardDatabase::class.java,
                    "cameraguard.db"
                ).fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
