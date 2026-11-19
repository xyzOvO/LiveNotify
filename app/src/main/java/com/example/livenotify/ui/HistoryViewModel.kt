package com.example.livenotify.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.livenotify.Graph
import com.example.livenotify.data.LiveSessionEntity
import com.example.livenotify.data.StreamerEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 开播历史页的状态：全部会话 + 当前关注的主播（用于筛选）。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val sessionDao = Graph.db.liveSessionDao()
    private val streamerDao = Graph.db.streamerDao()

    /** 全量会话；组合筛选在 UI 层用 filterSessions 纯函数完成，避免为每种筛选都写 DAO 查询 */
    val sessions: StateFlow<List<LiveSessionEntity>> = sessionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val streamers: StateFlow<List<StreamerEntity>> = streamerDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 清空历史，留给 UI 二次确认后调用 */
    fun clearAll() = viewModelScope.launch {
        sessionDao.deleteAll()
    }
}

/**
 * 历史页组合筛选（纯函数，UI 层调用，不改 DAO）：
 * - platformId: null=全部平台
 * - anchorKey: null=全部主播，否则 "platformId:anchorId"
 * - dayKey: null=全部日期，否则 "yyyy-MM-dd"
 * - query: 匹配主播昵称或直播标题
 */
internal fun filterSessions(
    sessions: List<LiveSessionEntity>,
    platformId: String?,
    anchorKey: String?,
    dayKey: String?,
    query: String
): List<LiveSessionEntity> {
    val q = query.trim()
    return sessions.filter { s ->
        (platformId == null || s.platformId == platformId) &&
            (anchorKey == null || "${s.platformId}:${s.anchorId}" == anchorKey) &&
            (dayKey == null || dayKeyOf(s.startedAtMs) == dayKey) &&
            (q.isEmpty() || s.nickname.contains(q, true) || s.title.contains(q, true))
    }
}

/** 会话按本地日期分组；返回 (日期键, 当天会话) 列表，日期倒序、组内按开始时刻倒序 */
internal fun groupByDate(sessions: List<LiveSessionEntity>): List<Pair<String, List<LiveSessionEntity>>> {
    if (sessions.isEmpty()) return emptyList()
    val sorted = sessions.sortedByDescending { it.startedAtMs }
    val out = ArrayList<Pair<String, List<LiveSessionEntity>>>()
    var key: String? = null
    var bucket = ArrayList<LiveSessionEntity>()
    for (s in sorted) {
        val d = dayKeyOf(s.startedAtMs)
        if (key == null || d != key) {
            if (key != null) out.add(key to bucket)
            key = d
            bucket = ArrayList()
        }
        bucket.add(s)
    }
    if (key != null) out.add(key to bucket)
    return out
}

/** yyyy-MM-dd（本地时区）；纯函数便于测试 */
internal fun dayKeyOf(ms: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "%04d-%02d-%02d".format(
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH)
    )
}

/** 直播时长文案：不足 1 分钟显示秒，否则显示「X 小时 Y 分」/「X 分钟」。 */
internal fun durationLabel(durationMs: Long): String {
    if (durationMs <= 0L) return "不足 1 分钟"
    val totalMin = durationMs / 60_000L
    if (totalMin < 1L) return "${(durationMs / 1000L).coerceAtLeast(1L)} 秒"
    val hours = totalMin / 60L
    val mins = totalMin % 60L
    return when {
        hours > 0L && mins > 0L -> "$hours 小时 $mins 分"
        hours > 0L -> "$hours 小时"
        else -> "$totalMin 分钟"
    }
}
