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
import java.util.Calendar

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
        data class Running(val percent: Int, val message: String, val rangeDays: Int) : AnalysisUi
        data class Done(
            val userId: Long,
            val rangeDays: Int,
            val data: UserAnalysis
        ) : AnalysisUi

        data class Failed(val message: String, val rangeDays: Int) : AnalysisUi
    }

    private val _analysis = MutableStateFlow<AnalysisUi>(AnalysisUi.Idle)
    val analysis: StateFlow<AnalysisUi> = _analysis

    private var analysisJob: kotlinx.coroutines.Job? = null

    /** 只保留这一次的结果缓存，避免重复点「开始分析」时反复请求服务端 */
    private var cachedKey: String? = null
    private var cachedData: UserAnalysis? = null

    /**
     * 分析某用户近 [days] 天的互动记录。
     * 服务端按 uid 扫历史场次，属于重查询，必须由用户显式触发。
     */
    fun analyze(userId: Long, days: Int) {
        if (userId <= 0L) return
        val key = "$userId:$days"
        if (cachedKey == key && cachedData != null) {
            _analysis.value = AnalysisUi.Done(userId, days, cachedData!!)
            return
        }
        analysisJob?.cancel()
        _analysis.value = AnalysisUi.Running(0, "创建分析任务…", days)
        analysisJob = viewModelScope.launch {
            try {
                val (from, to) = rangeOf(days)
                val data = BiliStatsApi.analyze(userId, from, to) { p, msg ->
                    _analysis.value = AnalysisUi.Running(p, msg, days)
                }
                cachedKey = key
                cachedData = data
                _analysis.value = AnalysisUi.Done(userId, days, data)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _analysis.value = AnalysisUi.Failed(t.userMessage(), days)
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
        _analysis.value = AnalysisUi.Idle
    }

    /**
     * 把「近 N 天」换算成服务端要的毫秒时间戳。
     *
     * days <= 0 表示「全部」。服务端对时间范围有下限：startTime=0 或 2019 年会直接返回
     * 「无效的日期范围」（2020-01-01 起可用）。所以这里用滚动的约 5.7 年窗口，
     * 既覆盖了绝大部分历史，也不会随着时间推移撞上那条边界。
     */
    private fun rangeOf(days: Int): Pair<Long, Long> {
        val end = System.currentTimeMillis()
        if (days <= 0) return (end - ALL_SPAN_MS) to end
        val cal = Calendar.getInstance().apply {
            timeInMillis = end
            add(Calendar.DAY_OF_YEAR, -days)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis to end
    }

    private companion object {
        /** 「全部」对应的回溯跨度：2100 天 ≈ 5.75 年，稳妥落在服务端可接受的下限之内 */
        const val ALL_SPAN_MS = 2100L * 24L * 3600L * 1000L
    }

    private fun Throwable.userMessage(): String = when (this) {
        is BiliApiException -> message ?: "请求失败"
        else -> message?.takeIf { it.isNotBlank() } ?: "网络请求失败，请稍后重试"
    }
}
