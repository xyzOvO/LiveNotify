package com.example.livenotify.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LiveSessionDao {

    /** 历史页：全部会话，按开始时刻倒序 */
    @Query("SELECT * FROM live_sessions ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<LiveSessionEntity>>

    /** 某主播的会话，按开始时刻倒序 */
    @Query("SELECT * FROM live_sessions WHERE platformId = :platformId AND anchorId = :anchorId ORDER BY startedAtMs DESC")
    fun observeFor(platformId: String, anchorId: String): Flow<List<LiveSessionEntity>>

    /** 某主播当前进行中（endAtMs=0）的会话，用于下播时收口；理论上最多一条 */
    @Query("SELECT * FROM live_sessions WHERE platformId = :platformId AND anchorId = :anchorId AND endAtMs = 0 ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun openSession(platformId: String, anchorId: String): LiveSessionEntity?

    @Insert
    suspend fun insert(session: LiveSessionEntity): Long

    @Update
    suspend fun update(session: LiveSessionEntity)

    /** 下播收口：写下播时刻与最后标题 */
    @Query("UPDATE live_sessions SET endAtMs = :endAtMs, title = :title WHERE id = :id")
    suspend fun close(id: Long, endAtMs: Long, title: String)

    /** 提醒送达状态更新 */
    @Query("UPDATE live_sessions SET notified = :notified, notifyFailed = :failed WHERE id = :id")
    suspend fun setNotifyState(id: Long, notified: Boolean, failed: Boolean)

    /** 下播补标题之外，直播中也要持续更新标题（主播可能换标题） */
    @Query("UPDATE live_sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    /** 清理 cutOff 之前开始、且已结束的会话；进行中的不删 */
    @Query("DELETE FROM live_sessions WHERE startedAtMs < :cutOff AND endAtMs > 0")
    suspend fun deleteOlderThan(cutOff: Long): Int

    @Query("SELECT COUNT(*) FROM live_sessions")
    suspend fun count(): Int

    @Query("DELETE FROM live_sessions")
    suspend fun deleteAll()
}
