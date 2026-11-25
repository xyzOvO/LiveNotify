package com.example.livenotify.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AppLogDao {
    @Insert
    suspend fun insert(log: AppLogEntity)

    /** 删除 cutoff 之前的旧日志（7 天过期） */
    @Query("DELETE FROM app_logs WHERE atMs < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /** 只保留最近 limit 条，多余的按时间从旧到新删掉 */
    @Query(
        "DELETE FROM app_logs WHERE id IN (" +
            "SELECT id FROM app_logs ORDER BY atMs DESC, id DESC LIMIT -1 OFFSET :keep" +
            ")"
    )
    suspend fun trimTo(keep: Int): Int

    @Query("SELECT COUNT(*) FROM app_logs")
    suspend fun count(): Int

    /** 诊断报告用：最近 N 条 WARN / ERROR */
    @Query(
        "SELECT * FROM app_logs WHERE severity IN ('WARN','ERROR') " +
            "ORDER BY atMs DESC, id DESC LIMIT :limit"
    )
    suspend fun recentProblems(limit: Int): List<AppLogEntity>

    @Query("DELETE FROM app_logs")
    suspend fun deleteAll()
}
