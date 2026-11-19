package com.example.livenotify.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    /** 分组管理页与「移动到…」的目标列表都走这里，顺序即用户看到的顺序 */
    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, name ASC")
    suspend fun listAll(): List<GroupEntity>

    /** 同名组不重复插入：新建时用户可能手输一个已有的名字 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(group: GroupEntity): Long

    @Query("DELETE FROM groups WHERE name = :name")
    suspend fun delete(name: String)

    @Query("UPDATE groups SET name = :newName WHERE name = :oldName")
    suspend fun rename(oldName: String, newName: String)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM groups")
    suspend fun maxSortOrder(): Int
}
