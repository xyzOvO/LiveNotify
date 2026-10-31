package com.example.livenotify.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.livenotify.Graph
import com.example.livenotify.bili.BiliApiException
import com.example.livenotify.bili.BiliStatsApi
import com.example.livenotify.bili.CareerStats
import com.example.livenotify.bili.ChannelStats
import com.example.livenotify.bili.TrendMonth
import com.example.livenotify.bili.UserAnalysis
import com.example.livenotify.bili.UserEvent
import com.example.livenotify.data.StreamerEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * B 站数据页的状态。
 *
 * 关注列表只取 B 站主播——其余平台的 uid 在对方数据源里没有对应记录，
 * 列表里混进来只会让人点了没反应。
 */
class StatsViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = Graph.db.streamerDao()

    /** 关注中的 B 站主播 */
    val biliStreamers: StateFlow<List<StreamerEntity>> = dao.observeAll()
        .map { list -> list.filter { it.platformId == "bilibili" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 主播数据（粉丝走势 / 生涯） ──────────────────────────────────
    data class ChannelUi(
        val loading: Boolean = false,
        val uid: Long = 0L,
        val channel: ChannelStats? = null,
        val trend: List<TrendMonth> = emptyList(),
        val career: CareerStats? = null,
        val error: String? = null
    )

    private val _channelUi = MutableStateFlow(ChannelUi())
    val channelUi: StateFlow<ChannelUi> = _channelUi

    private var channelJobToken = 0

    /** 读取某个主播的概览、粉丝走势与生涯统计；三者互不影响，单个失败不拖垮整页 */
    fun load(uid: Long) {
        if (uid <= 0L) return
        if (_channelUi.value.uid == uid && _channelUi.value.channel != null && !_channelUi.value.loading) return
        val token = ++channelJobToken
        _channelUi.value = ChannelUi(loading = true, uid = uid)
        viewModelScope.launch {
            var firstError: String? = null
            val result = coroutineScope {
                val ch = async { runCatching { BiliStatsApi.channel(uid) } }
                val tr = async { runCatching { BiliStatsApi.audienceTrend(uid) } }
                val ca = async { runCatching { BiliStatsApi.career(uid) } }
                Triple(ch.await(), tr.await(), ca.await())
            }
            if (token != channelJobToken) return@launch
            val channel = result.first.getOrNull()
            if (result.first.isFailure) firstError = result.first.exceptionOrNull()?.userMessage()
            val trend = result.second.getOrElse { emptyList() }
            val career = result.third.getOrNull()
            _channelUi.value = ChannelUi(
                loading = false,
                uid = uid,
                channel = channel,
                trend = trend,
                career = career,
                error = if (channel == null) firstError ?: "没有取到该主播的数据" else null
            )
        }
    }

    fun reload() {
        val uid = _channelUi.value.uid
        if (uid > 0) {
            _channelUi.value = ChannelUi(uid = uid)
            load(uid)
        }
    }

    // ── 用户互动事件分析 ────────────────────────────────────────────
    sealed interface AnalysisUi {
        data object Idle : AnalysisUi
        data class Running(
            val userId: Long,
            val percent: Int,
            val message: String,
            val rangeDays: Int
        ) : AnalysisUi
        data class Done(
            val userId: Long,
            val rangeDays: Int,
            val data: UserAnalysis,
            /** 实际提交给服务端的时间窗口，界面要把它显示出来，否则数字没有口径 */
            val fromMs: Long,
            val toMs: Long
        ) : AnalysisUi

        data class Failed(
            val userId: Long,
            val message: String,
            val rangeDays: Int
        ) : AnalysisUi
    }

    private val _analysis = MutableStateFlow<AnalysisUi>(AnalysisUi.Idle)
    val analysis: StateFlow<AnalysisUi> = _analysis

    private var analysisJob: kotlinx.coroutines.Job? = null

    /** 只保留这一次的结果缓存，避免重复点「开始分析」时反复请求服务端 */
    private var cachedKey: String? = null
    private var cachedData: UserAnalysis? = null
    private var cachedRange: Pair<Long, Long>? = null

    /**
     * 分析某用户近 [days] 天的互动记录。
     * 服务端按 uid 扫历史场次，属于重查询，必须由用户显式触发。
     *
     * [force] 为 true 时忽略缓存重新查询——「重新分析」按钮必须真的重新拉取，
     * 否则用户点了一年也还是那份旧结果。
     */
    fun analyze(userId: Long, days: Int, force: Boolean = false) {
        if (userId <= 0L) return
        // 逐条互动记录是轻查询，顺手带上；失败不影响分析主流程
        loadRecent(userId)
        val key = "$userId:$days"
        if (!force && cachedKey == key && cachedData != null) {
            val (cf, ct) = cachedRange ?: rangeOf(days)
            _analysis.value = AnalysisUi.Done(userId, days, cachedData!!, cf, ct)
            return
        }
        analysisJob?.cancel()
        _analysis.value = AnalysisUi.Running(userId, 0, "创建分析任务…", days)
        analysisJob = viewModelScope.launch {
            try {
                val (from, to) = rangeOf(days)
                val data = BiliStatsApi.analyze(userId, from, to) { p, msg ->
                    _analysis.value = AnalysisUi.Running(userId, p, msg, days)
                }
                cachedKey = key
                cachedData = data
                cachedRange = from to to
                _analysis.value = AnalysisUi.Done(userId, days, data, from, to)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _analysis.value = AnalysisUi.Failed(userId, t.userMessage(), days)
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        _analysis.value = AnalysisUi.Idle
    }

    fun clearAnalysis() {
        analysisJob?.cancel()
        cachedKey = null
        cachedData = null
        cachedRange = null
        _analysis.value = AnalysisUi.Idle
        _recent.value = RecentUi.Idle
    }

    // ── 最近的互动（逐条记录，按时间倒序） ──────────────────────────
    sealed interface RecentUi {
        data object Idle : RecentUi
        data class Loading(val userId: Long) : RecentUi
        data class Done(val userId: Long, val events: List<UserEvent>) : RecentUi
        data class Failed(val userId: Long, val message: String) : RecentUi
    }

    private val _recent = MutableStateFlow<RecentUi>(RecentUi.Idle)
    val recent: StateFlow<RecentUi> = _recent

    private var recentJob: kotlinx.coroutines.Job? = null

    /**
     * 拉取该用户最近的互动记录。
     *
     * 与分析任务独立：分析要扫历史场次可能失败，而逐条记录是轻查询，
     * 两边互不拖累。结果按 ts 倒序（最近在前），同一时刻的字段来自同一条
     * 服务端记录，不会串行错位。
     */
    fun loadRecent(userId: Long) {
        if (userId <= 0L) return
        recentJob?.cancel()
        _recent.value = RecentUi.Loading(userId)
        recentJob = viewModelScope.launch {
            try {
                val events = BiliStatsApi.recentEvents(userId)
                _recent.value = RecentUi.Done(userId, events)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _recent.value = RecentUi.Failed(userId, t.userMessage())
            }
        }
    }

    /**
     * 把「近 N 天」换算成服务端要的毫秒时间戳。
     *
     * days <= 0 表示「全部」。服务端对时间范围有下限：startTime=0 或 2019 年会直接返回
     * 「无效的日期范围」（2020-01-01 起可用）。所以这里用滚动的约 5.7 年窗口，
     * 既覆盖了绝大部分历史，也不会随着时间推移撞上那条边界。
     */
    internal fun rangeOf(days: Int): Pair<Long, Long> = analysisRange(days)

    private fun Throwable.userMessage(): String = when (this) {
        is BiliApiException -> message ?: "请求失败"
        else -> message?.takeIf { it.isNotBlank() } ?: "网络请求失败，请稍后重试"
    }
}

/** 分析结果必须与当前输入框里的 UID 和时间范围一致，不能把旧用户的数据挂到新输入下。 */
internal fun StatsViewModel.AnalysisUi.belongsTo(userId: Long, rangeDays: Int): Boolean = when (this) {
    StatsViewModel.AnalysisUi.Idle -> true
    is StatsViewModel.AnalysisUi.Running -> this.userId == userId && this.rangeDays == rangeDays
    is StatsViewModel.AnalysisUi.Done -> this.userId == userId && this.rangeDays == rangeDays
    is StatsViewModel.AnalysisUi.Failed -> this.userId == userId && this.rangeDays == rangeDays
}

internal fun StatsViewModel.RecentUi.belongsTo(userId: Long): Boolean = when (this) {
    StatsViewModel.RecentUi.Idle -> true
    is StatsViewModel.RecentUi.Loading -> this.userId == userId
    is StatsViewModel.RecentUi.Done -> this.userId == userId
    is StatsViewModel.RecentUi.Failed -> this.userId == userId
}
