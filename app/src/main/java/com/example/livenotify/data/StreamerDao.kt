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

    @Query("SELECT * FROM streamers WHERE platformId = :platformId AND anchorId = :anchorId")
    suspend fun findByAnchor(platformId: String, anchorId: String): StreamerEntity?

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
