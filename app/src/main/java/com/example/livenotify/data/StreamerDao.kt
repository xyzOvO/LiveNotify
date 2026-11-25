package com.example.livenotify.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StreamerDao {

    @Query("SELECT * FROM streamers ORDER BY isLive DESC, nickname ASC")
    fun observeAll(): Flow<List<StreamerEntity>>

    /**
     * 分组展示用：先按分组名（未分组的空串排最前），组内再按直播中、昵称排。
     * 后台检测仍走 listEnabled()，不受展示排序影响。
     */
    @Query("SELECT * FROM streamers ORDER BY `group` ASC, isLive DESC, nickname ASC")
    fun observeAllGrouped(): Flow<List<StreamerEntity>>

    @Query("SELECT * FROM streamers WHERE enabled = 1")
    suspend fun listEnabled(): List<StreamerEntity>

    /** 所有非空分组名（去重），供分组管理 / 移动目标列表 */
    @Query("SELECT DISTINCT `group` FROM streamers WHERE `group` != '' ORDER BY `group` ASC")
    fun observeGroups(): Flow<List<String>>

    /** 重命名分组：把组内所有主播改到新名 */
    @Query("UPDATE streamers SET `group` = :newName WHERE `group` = :oldName")
    suspend fun renameGroup(oldName: String, newName: String)

    /** 删除分组：组内主播回落「未分组」（空串），不删主播本身 */
    @Query("UPDATE streamers SET `group` = '' WHERE `group` = :name")
    suspend fun clearGroup(name: String)

    /** 把某位主播移到指定分组（空串=未分组） */
    @Query("UPDATE streamers SET `group` = :group WHERE id = :id")
    suspend fun setGroup(id: Long, group: String)

    /**
     * 主动清理过期的待补发提醒。
     *
     * 原来的判定是懒的：只有还查得到、还在播的主播才会在每轮 check() 里被判过期。
     * 监控关很久、主播被停用/删除时那条待办会一直挂在库里，诊断页永远显示旧值。
     * 这里按时间统一扫掉：超过 30 分钟或系统时间回拨（since > now）都清。
     * @return 实际清理的条数
     */
    @Query(
        """UPDATE streamers SET pendingNotifySession = 0, pendingNotifySince = 0
           WHERE pendingNotifySince > 0 AND (pendingNotifySince < :cutoff OR pendingNotifySince > :now)"""
    )
    suspend fun clearExpiredPending(cutoff: Long, now: Long): Int

    @Query("SELECT * FROM streamers WHERE platformId = :platformId AND anchorId = :anchorId")
    suspend fun findByAnchor(platformId: String, anchorId: String): StreamerEntity?

    /** 备份导入预览用：一次性取回所有已占用的 (平台, 主播) 键，避免逐条查库 */
    @Query("SELECT platformId, anchorId FROM streamers")
    suspend fun allAnchorKeys(): List<AnchorKey>

    data class AnchorKey(val platformId: String, val anchorId: String)

    /** 失败返回 -1（唯一索引冲突），调用方据此提示「已添加过」 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: StreamerEntity): Long

    /**
     * 批量写入，要么全进要么一条都不进（Room 会把一次 insert 包在事务里）。
     * 导入备份时用：中途写失败不能留下「只导进一半」的关注列表。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<StreamerEntity>)

    @Update
    suspend fun update(entity: StreamerEntity)

    /** 批量更新，减少事务开销 */
    @Update
    suspend fun updateAll(entities: List<StreamerEntity>)

    @Query("DELETE FROM streamers WHERE id = :id")
    suspend fun deleteById(id: Long)
}
