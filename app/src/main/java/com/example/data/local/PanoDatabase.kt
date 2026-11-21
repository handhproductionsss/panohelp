package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SceneEntity::class, TargetEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PanoDatabase : RoomDatabase() {
    abstract fun panoDao(): PanoDao

    companion object {
        @Volatile
        private var INSTANCE: PanoDatabase? = null

        fun getInstance(context: Context): PanoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PanoDatabase::class.java,
                    "panoguide_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
