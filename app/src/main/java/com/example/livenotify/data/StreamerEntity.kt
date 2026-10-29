package com.example.livenotify.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "streamers",
    indices = [Index(value = ["platformId", "anchorId"], unique = true)]
)
data class StreamerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platformId: String,          // bilibili / douyin / douyu
    val anchorId: String,            // 平台内稳定标识
    val displayId: String = anchorId,// 展示用房间号
    val nickname: String,
    val avatarUrl: String? = null,
    val title: String = "",
    val roomUrl: String,

    val isLive: Boolean = false,     // 最近一次成功检测到的开播状态
    val liveStreak: Int = 0,         // 连续检测到「开播」的次数，用于确认阈值
    val liveStartAt: Long = 0L,      // 本场开播时间戳（秒），仅直播中有值
    /** 上一场（或本场）开播时刻（秒），未开播时也保留，用于展示「距上次开播多久」 */
    val lastLiveStartAt: Long = 0L,
    /** 上一场下播时刻（毫秒），由引擎在检测到 开播→未开播 跳变时写入 */
    val lastLiveEndAt: Long = 0L,
    val lastNotifiedAt: Long = 0L,   // 上次推送时间，用于冷却
    val lastNotifiedLiveStart: Long = 0L, // 已推送过的场次标识，防止同一场重复推送
    val lastCheckedAt: Long = 0L,

    val missing: Boolean = false,    // 主播/房间不存在
    val errorCount: Int = 0,         // 连续异常次数
    val lastError: String? = null,

    val enabled: Boolean = true
)
