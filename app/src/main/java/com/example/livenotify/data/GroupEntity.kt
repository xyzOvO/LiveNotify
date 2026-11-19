package com.example.livenotify.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 分组名册。
 *
 * 分组原来是「主播行上的一个 group 字段」，没有任何独立存储——于是**空分组无处落脚**：
 * 用户新建一个组、还没往里放主播时，数据库里什么都不存在，`SELECT DISTINCT group`
 * 自然查不到它，回到列表就"消失"了（表现就是"新建分组没反应"）。
 *
 * 这张表只存组名和顺序，成员关系仍在 [StreamerEntity.group] 上（单归属，保持简单）。
 * 表里允许存在没有成员的组；删除组时同时清掉成员归属，但不删主播。
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val name: String,
    /** 显示顺序，越小越靠前；新建的排最后 */
    val sortOrder: Int = 0
)
