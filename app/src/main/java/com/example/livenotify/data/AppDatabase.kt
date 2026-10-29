package com.example.livenotify.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [StreamerEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun streamerDao(): StreamerDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 → v2：新增「上次开播/下播时间」两列。
         * 用 ALTER TABLE ADD COLUMN（带默认值）保留用户已有数据，不做破坏性重建。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE streamers ADD COLUMN lastLiveStartAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE streamers ADD COLUMN lastLiveEndAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "livenotify.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
