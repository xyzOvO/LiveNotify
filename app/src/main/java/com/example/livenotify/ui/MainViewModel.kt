package com.example.livenotify.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.livenotify.util.ShareText
import com.example.livenotify.Graph
import com.example.livenotify.data.EasterEggs
import com.example.livenotify.data.Settings
import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.monitor.MonitorService
import com.example.livenotify.monitor.NotificationHelper
import com.example.livenotify.platform.DouyinPlatform
import com.example.livenotify.platform.LiveState
import com.example.livenotify.platform.Outcome
import com.example.livenotify.platform.PlatformRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = Graph.db.streamerDao()

    val streamers: StateFlow<List<StreamerEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lastCheckedAt: StateFlow<Long> = streamers
        .map { it.maxOfOrNull { s -> s.lastCheckedAt } ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val settings: StateFlow<Settings> = Graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    /** 下拉刷新 / 立即检测的进行中状态 */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    fun consumeToast() { _toast.value = null }

    /** 供 UI 层主动提示（例如复制链接成功） */
    fun notify(message: String) { _toast.value = message }

    /** 关注问候：添加命中配置的主播后弹一次 */
    private val _egg = MutableStateFlow<EasterEggs.Egg?>(null)
    val egg: StateFlow<EasterEggs.Egg?> = _egg

    fun consumeEgg() { _egg.value = null }

    sealed interface AddResult {
        data class Success(val nickname: String) : AddResult
        data class Error(val message: String) : AddResult
    }

    /** 解析输入 → 拉取一次快照 → 落库。添加时若正在开播，也不会立刻推送（等下一场开播才提醒） */
    suspend fun add(raw: String, forcePlatformId: String?): AddResult {
        // 分享文案常带中文（「【xx的直播间】https://...」），先提取出链接/房间号
        val input = ShareText.extract(raw)
        val platform = forcePlatformId?.let { PlatformRegistry.get(it) }
            ?: PlatformRegistry.detect(input)
            ?: return AddResult.Error("无法识别平台，可粘贴直播间链接、房间号或直接粘贴分享文案")

        // 添加流程不经过监控引擎，需先同步最新 Cookie，避免使用进程启动时的空值/旧值。
        if (platform.id == DouyinPlatform.id) {
            DouyinPlatform.cookie = Graph.settings.current().douyinCookie
        }

        val anchorId = try {
            platform.resolve(input)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        } ?: return AddResult.Error(
                if (platform.id == "douyin")
                    "解析失败。抖音建议直接粘贴直播间链接（live.douyin.com/xxxx）"
                else "解析失败，未能在该链接/号码中取到主播标识"
            )

        if (dao.findByAnchor(platform.id, anchorId) != null) {
            return AddResult.Error("该主播已在关注列表中")
        }

        val outcome = try {
            platform.fetch(listOf(anchorId))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        }?.firstOrNull() ?: return AddResult.Error("获取主播信息失败，请检查网络后重试")

        val snap = when (outcome) {
            is Outcome.Ok -> outcome.snapshot
            is Outcome.Missing -> return AddResult.Error(outcome.reason)
            is Outcome.Failed -> return AddResult.Error(outcome.reason)
        }

        val entity = StreamerEntity(
            platformId = platform.id,
            anchorId = anchorId,
            displayId = snap.displayId,
            nickname = snap.nickname,
            avatarUrl = snap.avatarUrl,
            title = snap.title,
            roomUrl = snap.roomUrl,
            isLive = snap.state == LiveState.LIVE,
            liveStreak = if (snap.state == LiveState.LIVE) 1 else 0,
            liveStartAt = if (snap.state == LiveState.LIVE) snap.liveStartAt else 0L,
            // 部分平台（斗鱼）未开播也会返回上一场的开播时刻，添加时即写入，列表可直接显示「上次开播」
            lastLiveStartAt = snap.liveStartAt,
            lastCheckedAt = System.currentTimeMillis()
        )
        if (dao.insert(entity) == -1L) return AddResult.Error("该主播已在关注列表中")
        if (settings.value.monitorEnabled) {
            MonitorService.start(getApplication())
            // 常驻通知里的「共 N 位」要立刻跟上，否则要等下一轮轮询才变
            MonitorService.refresh(getApplication())
        }
        _toast.value = "已关注 ${snap.nickname}"
        // 命中配置就排队弹一句，落款用接口返回的真实昵称
        EasterEggs.find(platform.id, anchorId, snap.displayId)?.let { hit ->
            _egg.value = hit.copy(nickname = snap.nickname)
        }
        return AddResult.Success(snap.nickname)
    }

    fun remove(s: StreamerEntity) = viewModelScope.launch {
        dao.deleteById(s.id)
        MonitorService.refreshIfRunning(getApplication())
    }

    fun setMonitorEnabled(enabled: Boolean) = viewModelScope.launch {
        Graph.settings.setMonitorEnabled(enabled)
        if (enabled) MonitorService.start(getApplication()) else MonitorService.stop(getApplication())
    }

    /** 立即跑一轮（不依赖服务状态） */
    fun checkNow() = viewModelScope.launch {
        if (_refreshing.value) return@launch
        _refreshing.value = true
        try {
            val r = Graph.engine.runOnce()
            _toast.value = when {
                r.checked == 0 -> "暂无关注的主播"
                else -> "已检测 ${r.checked} 位" + (if (r.errors > 0) "，${r.errors} 项异常" else "，一切正常")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            _toast.value = "检测失败：${t.message ?: "未知错误"}"
        } finally {
            _refreshing.value = false
            // 手动检测不走服务循环，这里顺手把常驻通知的时间戳刷掉
            MonitorService.refreshIfRunning(getApplication())
        }
    }

    fun openRoom(s: StreamerEntity) {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(s.roomUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun setPollInterval(sec: Int) = viewModelScope.launch { Graph.settings.setPollInterval(sec) }
    fun setMobileFactor(f: Int) = viewModelScope.launch { Graph.settings.setMobileFactor(f) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { Graph.settings.setWifiOnly(v) }
    fun setQuiet(enabled: Boolean, start: Int, end: Int, noNotify: Boolean) =
        viewModelScope.launch { Graph.settings.setQuiet(enabled, start, end, noNotify) }
    fun setConfirmTimes(n: Int) = viewModelScope.launch { Graph.settings.setConfirmTimes(n) }
    fun setCooldownMin(n: Int) = viewModelScope.launch { Graph.settings.setCooldownMin(n) }
    fun setDouyinCookie(c: String) = viewModelScope.launch { Graph.settings.setDouyinCookie(c) }

    /** 开播提醒响铃。Android 8+ 由渠道决定，改完必须重建渠道才生效 */
    fun setNotifySound(v: Boolean) = viewModelScope.launch {
        Graph.settings.setNotifySound(v)
        NotificationHelper.applyAlertSettings(getApplication(), v, Graph.settings.current().notifyVibrate)
    }

    fun setNotifyVibrate(v: Boolean) = viewModelScope.launch {
        Graph.settings.setNotifyVibrate(v)
        NotificationHelper.applyAlertSettings(getApplication(), Graph.settings.current().notifySound, v)
    }

    /** 隐藏常驻通知：服务仍在跑，只是通知降到最低可见度 */
    fun setHideMonitorNotification(v: Boolean) = viewModelScope.launch {
        Graph.settings.setHideMonitorNotification(v)
        MonitorService.refreshIfRunning(getApplication())
    }
}
