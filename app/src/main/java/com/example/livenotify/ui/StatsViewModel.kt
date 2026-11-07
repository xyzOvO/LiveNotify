package com.example.livenotify.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.livenotify.Graph
import com.example.livenotify.bili.BiliApiException
import com.example.livenotify.bili.BiliStatsApi
import com.example.livenotify.bili.CareerStats
import com.example.livenotify.bili.ChannelStats
import com.example.livenotify.bili.StatsCache
import com.example.livenotify.bili.StatsCacheStore
import com.example.livenotify.bili.StatsEndpoint
import com.example.livenotify.bili.TrendMonth
import com.example.livenotify.bili.UserAnalysis
import com.example.livenotify.bili.UserEvent
import com.example.livenotify.data.StreamerEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B 站数据页的状态。
 *
 * 关注列表只取 B 站主播——其余平台的 uid 在对方数据源里没有对应记录，
 * 列表里混进来只会让人点了没反应。
 */
class StatsViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = Graph.db.streamerDao()

    /** 数据页的本地缓存：磁盘上按主播 / 按分析任务各存一份 */
    private val cache = StatsCacheStore(app.applicationContext.filesDir)

    /** 数据接口当前线路，界面页脚直接显示，用户能看出自己是不是被切到了备用节点 */
    private val _lineLabel = MutableStateFlow(BiliStatsApi.lineLabel())
    val lineLabel: StateFlow<String> = _lineLabel

    init {
        // 网络层不持有 Context；线路变化时由 ViewModel 落盘，下次启动优先试这个域
        BiliStatsApi.onOriginChanged = { origin ->
            _lineLabel.value = StatsEndpoint.label(origin)
            viewModelScope.launch { runCatching { Graph.settings.setStatsApiOrigin(origin) } }
        }
        viewModelScope.launch {
            val saved = runCatching { Graph.settings.current().statsApiOrigin }.getOrDefault("")
            BiliStatsApi.setPreferredOrigin(saved.takeIf { it.isNotBlank() })
        }
    }

    override fun onCleared() {
        BiliStatsApi.onOriginChanged = null
        super.onCleared()
    }

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
        val error: String? = null,
        /** 这份数据是什么时候取回来的；0 表示还没有任何数据 */
        val savedAt: Long = 0L,
        /** 本次展示的是磁盘缓存（没有发起请求） */
        val fromCache: Boolean = false
    )

    private val _channelUi = MutableStateFlow(ChannelUi())
    val channelUi: StateFlow<ChannelUi> = _channelUi

    private var channelJobToken = 0

    /**
     * 读取某个主播的概览、粉丝走势与生涯统计。
     *
     * 有缓存就直接展示缓存，**不再自动请求**：这个数据源是第三方社区接口，
     * 有 IP 频率限制，来回切主播不该每次都打一遍。要最新数据得用户自己点「刷新」。
     * 缓存不存在（第一次看这个主播）时照常请求。
     */
    fun load(uid: Long, force: Boolean = false) {
        if (uid <= 0L) return
        val cur = _channelUi.value
        if (!force && cur.uid == uid && cur.channel != null && !cur.loading) return
        val token = ++channelJobToken
        viewModelScope.launch {
            val keep = _channelUi.value.takeIf { it.uid == uid }
            if (!force) {
                val cached = withContext(Dispatchers.IO) { cache.readChannel(uid) }
                if (token != channelJobToken) return@launch
                if (cached != null) {
                    _channelUi.value = ChannelUi(
                        uid = uid,
                        channel = cached.channel,
                        trend = cached.trend,
                        career = cached.career,
                        savedAt = cached.savedAt,
                        fromCache = true
                    )
                    return@launch
                }
            }
            // 刷新时保留旧内容，只在顶部转圈：整页闪成骨架屏比看着旧数据更让人焦躁
            _channelUi.value = ChannelUi(
                loading = true,
                uid = uid,
                channel = keep?.channel,
                trend = keep?.trend ?: emptyList(),
                career = keep?.career,
                savedAt = keep?.savedAt ?: 0L,
                fromCache = keep?.channel != null
            )

            var firstError: String? = null
            val result = coroutineScope {
                val ch = async { runCatching { BiliStatsApi.channel(uid) } }
                val tr = async { runCatching { BiliStatsApi.audienceTrend(uid) } }
                val ca = async { runCatching { BiliStatsApi.career(uid) } }
                Triple(ch.await(), tr.await(), ca.await())
            }
            if (token != channelJobToken) return@launch
            val fresh = result.first.getOrNull()
            if (result.first.isFailure) firstError = result.first.exceptionOrNull()?.userMessage()
            // 服务端偶尔只回一半字段（实测刷新时 uName/faceUrl 会是空的）：
            // 展示用的身份信息不能因为一次残缺响应就被清掉，否则界面只剩「UID xxx」
            val prevChannel = keep?.channel
            val channel = if (fresh != null && prevChannel != null) fresh.copy(
                name = fresh.name.ifBlank { prevChannel.name },
                faceUrl = fresh.faceUrl.ifBlank { prevChannel.faceUrl },
                sign = fresh.sign.ifBlank { prevChannel.sign }
            ) else fresh
            val trend = result.second.getOrElse { emptyList() }
            val career = result.third.getOrNull()
            if (channel != null) {
                val now = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    runCatching {
                        cache.writeChannel(
                            StatsCache.ChannelEntry(now, channel, trend, career)
                        )
                    }
                }
                _channelUi.value = ChannelUi(
                    loading = false, uid = uid, channel = channel,
                    trend = trend, career = career, savedAt = now
                )
            } else {
                // 请求失败：旧数据留着，只在上面挂一条失败原因
                val prev = keep?.channel
                _channelUi.value = ChannelUi(
                    loading = false,
                    uid = uid,
                    channel = prev,
                    trend = if (prev != null) keep.trend else emptyList(),
                    career = if (prev != null) keep.career else null,
                    savedAt = keep?.savedAt ?: 0L,
                    fromCache = prev != null,
                    error = firstError ?: "没有取到该主播的数据"
                )
            }
        }
    }

    /** 用户主动刷新：绕过缓存重新请求 */
    fun reload() {
        val uid = _channelUi.value.uid
        if (uid > 0) load(uid, force = true)
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
            val toMs: Long,
            /** 结果保存时刻；0 表示这次刚取回来 */
            val savedAt: Long = 0L,
            /** 直接读的本地缓存，本次没有请求服务端 */
            val fromCache: Boolean = false
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
     * 服务端按 uid 扫历史场次，属于重查询，必须由用户显式触发；
     * 同一个「uid + 时间范围」有缓存时直接用缓存，[force] 才真的重新请求。
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
                if (!force) {
                    val cached = withContext(Dispatchers.IO) { cache.readAnalysis(userId, days) }
                    if (cached != null && cached.userId == userId && cached.rangeDays == days) {
                        _analysis.value = AnalysisUi.Done(
                            userId, days, cached.data, cached.fromMs, cached.toMs,
                            savedAt = cached.savedAt, fromCache = true
                        )
                        return@launch
                    }
                }
                val (from, to) = rangeOf(days)
                val data = BiliStatsApi.analyze(userId, from, to) { p, msg ->
                    _analysis.value = AnalysisUi.Running(userId, p, msg, days)
                }
                val now = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    runCatching {
                        cache.writeAnalysis(
                            StatsCache.AnalysisEntry(userId, days, now, from, to, data)
                        )
                    }
                }
                cachedKey = key
                cachedData = data
                cachedRange = from to to
                _analysis.value = AnalysisUi.Done(userId, days, data, from, to, savedAt = now)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _analysis.value = AnalysisUi.Failed(userId, t.userMessage(), days)
            }
        }
    }

    /**
     * 切主播 / 切时间范围时先把缓存结果亮出来。
     *
     * 分析任务动辄十几秒，不该因为「换了个 Tab 再切回来」就重跑一遍。
     * 只有当前没有对应结果时才读缓存，正在跑的任务不受影响。
     */
    fun restoreAnalysis(userId: Long, days: Int) {
        if (userId <= 0L || days <= 0) return
        val cur = _analysis.value
        if (cur is AnalysisUi.Running) return
        if (cur is AnalysisUi.Done && cur.userId == userId && cur.rangeDays == days) return
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { cache.readAnalysis(userId, days) } ?: return@launch
            if (cached.userId != userId || cached.rangeDays != days) return@launch
            val now = _analysis.value
            if (now is AnalysisUi.Running) return@launch
            if (now is AnalysisUi.Done && now.userId == userId && now.rangeDays == days) return@launch
            _analysis.value = AnalysisUi.Done(
                userId, days, cached.data, cached.fromMs, cached.toMs,
                savedAt = cached.savedAt, fromCache = true
            )
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
