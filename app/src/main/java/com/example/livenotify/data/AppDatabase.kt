package com.example.livenotify.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [StreamerEntity::class], version = 3, exportSchema = false)
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

        /**
         * v2 → v3：新增 note 列，存平台附注（B 站「轮播中」、斗鱼分区名等）。
         * 之前这个信息在解析层就丢了，导致轮播直播间被当成「未开播」，界面上看不出原因。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE streamers ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "livenotify.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
