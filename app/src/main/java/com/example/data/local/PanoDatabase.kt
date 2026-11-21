package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SceneEntity::class, TargetEntity::class],
    version = 2,
    exportSchema = false
)
abstract class PanoDatabase : RoomDatabase() {
    abstract fun panoDao(): PanoDao

    companion object {
        @Volatile
        private var INSTANCE: PanoDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scenes ADD COLUMN isUltrawide INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scenes ADD COLUMN stitchedImagePath TEXT")
                db.execSQL("ALTER TABLE scenes ADD COLUMN stitchedSavedName TEXT")
            }
        }

        fun getInstance(context: Context): PanoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PanoDatabase::class.java,
                    "panoguide_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
