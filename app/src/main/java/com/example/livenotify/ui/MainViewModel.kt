package com.example.livenotify.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.livenotify.util.BackupFormat
import com.example.livenotify.util.ShareText
import com.example.livenotify.Graph
import com.example.livenotify.data.EasterEggs
import com.example.livenotify.data.Settings
import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.monitor.MonitorService
import com.example.livenotify.monitor.NotificationHelper
import com.example.livenotify.monitor.resumeBaseline
import com.example.livenotify.BuildConfig
import com.example.livenotify.platform.DouyinPlatform
import com.example.livenotify.platform.LiveState
import com.example.livenotify.platform.Outcome
import com.example.livenotify.platform.PlatformRegistry
import com.example.livenotify.update.ApkDownloader
import com.example.livenotify.update.ApkInstaller
import com.example.livenotify.update.ReleaseInfo
import com.example.livenotify.update.UpdateApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
                    "解析失败。抖音建议直接粘贴直播间链接，或把 App 分享出来的整段文案粘进来"
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
        val monitoring = settings.value.monitorEnabled
        if (monitoring) {
            MonitorService.start(getApplication())
            // 常驻通知里的「共 N 位」要立刻跟上，否则要等下一轮轮询才变
            MonitorService.refresh(getApplication())
        }
        // 监控关闭时得说清楚，否则用户会以为加上了就会自动提醒
        _toast.value = if (monitoring) "已关注 ${snap.nickname}"
        else "已关注 ${snap.nickname}，但后台检测已关闭，不会自动提醒"
        // 命中配置就排队弹一句，落款用接口返回的真实昵称
        EasterEggs.find(platform.id, anchorId, snap.displayId)?.let { hit ->
            _egg.value = hit.copy(nickname = snap.nickname)
        }
        return AddResult.Success(snap.nickname)
    }

    // ── 关注列表备份 / 恢复 ─────────────────────────────────────────

    /** 导出用的 JSON 字符串。只含平台/主播 ID、暂停状态与白名单设置 */
    suspend fun exportBackup(): String {
        val all = dao.observeAll().first()
        val s = Graph.settings.current()
        return BackupFormat.encode(BackupFormat.snapshotOf(all, s, System.currentTimeMillis()))
    }

    /** 导入结果：逐条处理，单条失败不影响其余条目 */
    data class ImportReport(val added: Int, val skipped: Int, val failed: List<String>)

    /**
     * 导入备份。不直接把 JSON 字段塞进数据库——那会把旧设备的开播状态、
     * 已通知场次一起带过来，下一轮轮询就可能补推一条过期提醒。
     *
     * 每条都重新向平台取一次快照，再用 resumeBaseline 初始化通知基准；
     * 已存在的主播保持原样（含用户后来改过的暂停状态），只计入 skipped。
     */
    suspend fun importBackup(text: String): ImportReport {
        val data = try {
            BackupFormat.decode(text)
        } catch (t: Throwable) {
            return ImportReport(0, 0, listOf(t.message ?: "备份文件无法解析"))
        }
        var added = 0
        var skipped = 0
        val failed = ArrayList<String>()
        for (e in data.entries) {
            val platform = PlatformRegistry.get(e.platformId)
            if (platform == null) {
                failed.add("未知平台 ${e.platformId} · ${e.anchorId}")
                continue
            }
            if (dao.findByAnchor(e.platformId, e.anchorId) != null) {
                skipped++
                continue
            }
            if (platform.id == DouyinPlatform.id) {
                DouyinPlatform.cookie = Graph.settings.current().douyinCookie
            }
            val outcome = try {
                platform.fetch(listOf(e.anchorId))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                null
            }?.firstOrNull()
            val snap = when (outcome) {
                is Outcome.Ok -> outcome.snapshot
                is Outcome.Missing -> {
                    failed.add("${platform.displayName} ${e.anchorId}：${outcome.reason}")
                    continue
                }
                is Outcome.Failed -> {
                    failed.add("${platform.displayName} ${e.anchorId}：${outcome.reason}")
                    continue
                }
                null -> {
                    failed.add("${platform.displayName} ${e.anchorId}：获取失败")
                    continue
                }
            }
            // 用平台刚返回的真实信息建行，不走解析链；通知基准对齐当前场次
            val fresh = StreamerEntity(
                platformId = platform.id,
                anchorId = e.anchorId,
                nickname = snap.nickname.ifBlank { e.anchorId },
                roomUrl = snap.roomUrl
            )
            val entity = resumeBaseline(fresh, snap, System.currentTimeMillis(), enabled = e.enabled)
            if (dao.insert(entity) == -1L) {
                skipped++
            } else {
                added++
            }
        }
        // 设置最后写：某些项（监控开关）会影响服务启停，别让中途状态反复横跳
        try {
            data.prefs.applyTo(Graph.settings)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            failed.add("设置恢复失败：${t.message ?: "未知错误"}")
        }
        if (added > 0) {
            MonitorService.refreshIfRunning(getApplication())
            if (Graph.settings.current().monitorEnabled) MonitorService.start(getApplication())
        }
        return ImportReport(added, skipped, failed)
    }

    fun remove(s: StreamerEntity) = viewModelScope.launch {
        dao.deleteById(s.id)
        MonitorService.refreshIfRunning(getApplication())
    }

    /**
     * 暂停 / 恢复某位主播的关注。返回 null 表示成功，否则是可直接展示的失败原因。
     *
     * 暂停只改 enabled：引擎用 listEnabled() 取数，自然不会再检测它；卡片本身仍在
     * observeAll() 里，不会消失。同时清掉「直播中」这类会误导的旧状态。
     *
     * 恢复比较讲究：暂停期间主播可能下播又重开，若直接把 enabled 置回 true，
     * LiveTransition 会因为场次 key 变化判定「新场次」而立刻推一条过期提醒。
     * 所以先拉一次当前快照作为基准，并把当前场次记为「已通知」，再恢复监控。
     * 拉取失败就不恢复，宁可让用户重试，也不制造误报。
     */
    suspend fun setStreamerEnabled(s: StreamerEntity, enabled: Boolean): String? {
        if (!enabled) {
            dao.update(
                s.copy(
                    enabled = false,
                    isLive = false,
                    liveStreak = 0,
                    liveStartAt = 0L
                )
            )
            MonitorService.refreshIfRunning(getApplication())
            return null
        }

        val platform = PlatformRegistry.get(s.platformId)
            ?: return "找不到该主播的平台配置，无法恢复"
        if (platform.id == DouyinPlatform.id) {
            DouyinPlatform.cookie = Graph.settings.current().douyinCookie
        }
        val outcome = try {
            platform.fetch(listOf(s.anchorId))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        }?.firstOrNull() ?: return "获取直播状态失败，请检查网络后再恢复"

        val snap = when (outcome) {
            is Outcome.Ok -> outcome.snapshot
            is Outcome.Missing -> return outcome.reason
            is Outcome.Failed -> return outcome.reason
        }
        val now = System.currentTimeMillis()
        dao.update(resumeBaseline(s, snap, now))
        MonitorService.refreshIfRunning(getApplication())
        return null
    }

    /**
     * 撤销移除：重新插入一行。
     * 用新 id（copy(id = 0)）而不是沿用旧 id——旧行可能已被复用，且唯一索引冲突时 insert 会静默忽略，
     * 用新 id 至少能保证「没加过就恢复成功」。
     */
    fun restore(s: StreamerEntity) = viewModelScope.launch {
        if (dao.insert(s.copy(id = 0)) != -1L) {
            MonitorService.refreshIfRunning(getApplication())
        }
    }

    fun setMonitorEnabled(enabled: Boolean) = viewModelScope.launch {
        Graph.settings.setMonitorEnabled(enabled)
        if (!enabled) {
            MonitorService.stop(getApplication())
            return@launch
        }
        // 启动失败（后台启动受限 / 通知权限被关）必须说出来，否则用户以为在监控
        if (!MonitorService.start(getApplication())) {
            _toast.value = "后台检测启动失败：请允许自启动 / 后台运行后重试"
        }
    }

    /** 服务的真实运行状态，界面用它显示「监控中」（设置项开着不等于服务在跑） */
    val monitorRunning: StateFlow<Boolean> = MonitorService.running

    /**
     * 回到前台时补一次：服务可能被系统或 ROM 回收，界面要反映真实状态，
     * 设置里开着却没跑就顺手重新拉起。
     */
    fun ensureMonitorRunning() {
        if (!settings.value.monitorEnabled) return
        if (MonitorService.running.value) return
        if (!MonitorService.start(getApplication())) {
            _toast.value = "后台检测启动失败：请允许自启动 / 后台运行"
        }
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
    // ── 应用内更新 ────────────────────────────────────────────
    sealed interface UpdateState {
        object Idle : UpdateState
        object Checking : UpdateState
        object UpToDate : UpdateState
        /** downloading=true 时 percent 为进度；否则展示更新说明与下载按钮 */
        data class Available(val info: ReleaseInfo, val downloading: Boolean = false, val percent: Int = 0) : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update

    private val updateLimiter = com.example.livenotify.net.RateLimiter(5_000L)

    fun dismissUpdate() { _update.value = UpdateState.Idle }

    /** 手动检查更新：无论结果如何都给提示 */
    fun checkUpdate() = viewModelScope.launch {
        val s = _update.value
        if (s is UpdateState.Checking) return@launch
        if (s is UpdateState.Available && s.downloading) return@launch
        _update.value = UpdateState.Checking
        _update.value = when (val info = UpdateApi.latest()) {
            null -> UpdateState.Failed("检查更新失败，请检查网络后重试")
            else -> if (info.versionCode > BuildConfig.VERSION_CODE.toLong()) UpdateState.Available(info)
            else UpdateState.UpToDate
        }
    }

    /** 启动时自动检查一次：没更新就静默，有更新才弹 */
    private var autoChecked = false

    fun autoCheckUpdate() = viewModelScope.launch {
        if (autoChecked) return@launch
        autoChecked = true
        if (!Graph.settings.current().autoUpdateCheck) return@launch
        // 用户已经手动查过（或正在下载）就不要用自动检查的结果覆盖
        if (_update.value !is UpdateState.Idle) return@launch
        val info = UpdateApi.latest() ?: return@launch
        if (info.versionCode > BuildConfig.VERSION_CODE.toLong()) {
            _update.value = UpdateState.Available(info)
        }
    }

    /** 下载新版本并拉起系统安装界面 */
    fun downloadUpdate(info: ReleaseInfo) = viewModelScope.launch {
        if ((_update.value as? UpdateState.Available)?.downloading == true) return@launch
        _update.value = UpdateState.Available(info, downloading = true, percent = 0)
        val file = ApkDownloader.download(getApplication(), info.apkUrl, info.versionName) { p ->
            _update.value = UpdateState.Available(info, downloading = true, percent = p)
        }
        if (file == null) {
            _update.value = UpdateState.Failed("下载失败，请检查网络后重试")
            return@launch
        }
        // 包名 / 签名 / 版本一次校验到位；坏包要删掉，否则下次点「重试」还会复用同一个坏文件
        when (val checked = ApkInstaller.check(getApplication(), file, info.versionCode)) {
            is ApkInstaller.ApkCheck.Rejected -> {
                runCatching { file.delete() }
                _update.value = UpdateState.Failed(checked.reason)
                return@launch
            }
            is ApkInstaller.ApkCheck.Ok -> Unit
        }
        if (!ApkInstaller.canInstall(getApplication())) {
            ApkInstaller.openInstallPermission(getApplication())
            _update.value = UpdateState.Failed("请先在打开的页面里允许「安装未知应用」，再回来重试")
            return@launch
        }
        if (!ApkInstaller.install(getApplication(), file)) {
            _update.value = UpdateState.Failed("没有找到可用的安装程序，请到 Releases 页手动下载")
            return@launch
        }
        // 安装页已推到前台，关掉弹窗；用户返回时若没装成，可在设置页再点一次（会复用已下载的包）
        _update.value = UpdateState.Idle
    }

    fun setDouyinCookie(c: String) = viewModelScope.launch {
        Graph.settings.setDouyinCookie(c)
        _toast.value = if (c.isBlank()) "已清空 Cookie" else "Cookie 已保存到本机"
    }

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

    fun setAutoUpdateCheck(v: Boolean) = viewModelScope.launch {
        Graph.settings.setAutoUpdateCheck(v)
    }
}
