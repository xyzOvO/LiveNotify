package com.example.livenotify.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [StreamerEntity::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun streamerDao(): StreamerDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * 每个版本增量对应的 SQL，[Pair] 键是 (from, to)。
         *
         * 单独列出来不只是为了下面的 Migration 复用：`MigrationSqlTest` 用真实 SQLite
         * 从 v1/v2/v3 建表、跑同一份 SQL，再逐列对照 Room 导出的 schema JSON——
         * 将来改 [StreamerEntity] 忘了写迁移，测试会直接红掉，而不是在用户手机上静默丢数据。
         */
        val MIGRATION_SQL: Map<Pair<Int, Int>, List<String>> = mapOf(
            // v1 → v2：新增「上次开播 / 下播时间」两列
            (1 to 2) to listOf(
                "ALTER TABLE streamers ADD COLUMN lastLiveStartAt INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE streamers ADD COLUMN lastLiveEndAt INTEGER NOT NULL DEFAULT 0"
            ),
            // v2 → v3：新增 note 列（B 站「轮播中」、斗鱼分区名等平台附注）
            (2 to 3) to listOf(
                "ALTER TABLE streamers ADD COLUMN note TEXT NOT NULL DEFAULT ''"
            ),
            // v3 → v4：新增「待补发的开播提醒」两列；服务被杀、App 重启后仍能接着补
            (3 to 4) to listOf(
                "ALTER TABLE streamers ADD COLUMN pendingNotifySession INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE streamers ADD COLUMN pendingNotifySince INTEGER NOT NULL DEFAULT 0"
            )
        )

        private fun migrationOf(from: Int, to: Int): Migration = object : Migration(from, to) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_SQL.getValue(from to to).forEach(db::execSQL)
            }
        }

        private val MIGRATION_1_2 = migrationOf(1, 2)
        private val MIGRATION_2_3 = migrationOf(2, 3)
        private val MIGRATION_3_4 = migrationOf(3, 4)

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "livenotify.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
