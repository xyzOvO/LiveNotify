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

    @Query("SELECT * FROM streamers WHERE enabled = 1")
    suspend fun listEnabled(): List<StreamerEntity>

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
