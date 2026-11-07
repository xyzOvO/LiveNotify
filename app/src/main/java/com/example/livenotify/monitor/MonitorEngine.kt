package com.example.livenotify.monitor

import android.content.Context
import android.util.Log
import com.example.livenotify.data.AppDatabase
import com.example.livenotify.data.Settings
import com.example.livenotify.data.SettingsRepository
import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.platform.LiveState
import com.example.livenotify.platform.Outcome
import com.example.livenotify.platform.PlatformRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 检测引擎：一次轮询 = 按平台分组 → 批量拉取 → 状态对比 → 去重 → 推送 → 落库。
 *
 * 防误报 / 防重复推送的三道闸：
 *   1. 状态跳变：上次未开播，或同一主播换了一个场次 key，才可能推送；
 *   2. 连续确认：连续 [Settings.confirmTimes] 次检测到开播（抖动过滤）；
 *   3. 场次去重 + 冷却：同一场次（liveStartAt 相同）只推一次，且受冷却时间约束。
 * 网络异常或解析失败时**不改动已知状态**，只累计错误，避免把「没查到」当成「没开播」。
 */
class MonitorEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository
) {
    data class Report(
        val checked: Int = 0,
        val notified: Int = 0,
        val errors: Int = 0,
        /** 本轮结束后仍未送达、还在有效期内的开播提醒数（通知被关时用来解释「为什么没收到」） */
        val pending: Int = 0,
        /**
         * 本轮应该检测的主播数。
         * 跳过时 [checked] 恒为 0，只用 checked 会把「有 20 位主播但当前不是 Wi-Fi」
         * 说成「暂无关注」，所以单独给出这个值。
         */
        val total: Int = 0,
        /** 非空表示本轮什么都没查：没有网络，或「仅 Wi-Fi」下当前不是 Wi-Fi */
        val skip: SkipReason? = null,
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L
    ) {
        val durationMs: Long get() = (finishedAt - startedAt).coerceAtLeast(0L)
    }

    enum class SkipReason {
        /** 「仅 Wi-Fi 下检测」开着，当前不是 Wi-Fi */
        WIFI_ONLY,

        /** 没有可用网络（未连接，或连上了但没有出口） */
        NO_NETWORK
    }

    @Volatile
    var lastRunAt: Long = 0L
        private set

    /**
     * 上一轮被跳过的原因；界面据此说「等待网络」而不是「最近检测」。
     * 用 StateFlow 而不是普通字段：后台轮询在服务里跑，主页要能跟着改口。
     */
    private val _lastSkip = MutableStateFlow<SkipReason?>(null)
    val lastSkip: StateFlow<SkipReason?> = _lastSkip

    /** 上一轮实际耗时（含全部网络请求），用来解释「设置 2 分钟为什么两轮隔了 3 分钟」 */
    @Volatile
    var lastDurationMs: Long = 0L
        private set

    /** 后台轮询和手动刷新共用同一状态机，必须串行执行，避免重复通知和旧快照覆盖新结果。 */
    private val runMutex = Mutex()

    suspend fun runOnce(): Report = runMutex.withLock {
        val now = System.currentTimeMillis()
        val cfg = settings.current()
        // 抖音降级路径需要用户自备 Cookie（可选）
        com.example.livenotify.platform.DouyinPlatform.cookie = cfg.douyinCookie

        val dao = db.streamerDao()
        val all = dao.listEnabled()
        if (all.isEmpty()) {
            lastRunAt = now
            return Report(startedAt = now, finishedAt = now)
        }

        // 没网 / 网络连上了但没有出口：整轮跳过。一次请求都不发，
        // 既不累加主播的错误次数，也不改开播状态——「查不到」不等于「没开播」。
        val net = NetworkState.read(context)
        val skip = skipReason(cfg.wifiOnly, net)
        if (skip != null) {
            _lastSkip.value = skip
            return Report(
                total = all.size,
                skip = skip,
                startedAt = now,
                finishedAt = System.currentTimeMillis()
            )
        }
        _lastSkip.value = null

        val quiet = inQuietWindow(cfg, now)
        val updates = ArrayList<StreamerEntity>(all.size)
        var notified = 0
        var errors = 0

        for ((platformId, items) in all.groupBy { it.platformId }) {
            val platform = PlatformRegistry.get(platformId)
            if (platform == null) {
                items.forEach {
                    updates.add(it.copy(errorCount = it.errorCount + 1, lastError = "未知平台：$platformId"))
                }
                errors += items.size
                continue
            }

            val outcomes = try {
                platform.fetch(items.map { it.anchorId })
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "fetch failed on ${platform.id}", t)
                emptyList()
            }.associateBy { it.anchorId }

            for (s in items) {
                when (val o = outcomes[s.anchorId]) {
                    null -> {
                        errors++
                        updates.add(
                            s.copy(
                                lastCheckedAt = now,
                                errorCount = s.errorCount + 1,
                                lastError = "未返回检测结果"
                            )
                        )
                    }

                    is Outcome.Missing -> {
                        // 主播注销 / 房间删除：明确标记，不再推送，保留条目等待用户处理
                        updates.add(
                            s.copy(
                                missing = true,
                                isLive = false,
                                liveStreak = 0,
                                pendingNotifySession = 0L,
                                pendingNotifySince = 0L,
                                lastCheckedAt = now,
                                errorCount = 0,
                                lastError = o.reason
                            )
                        )
                    }

                    is Outcome.Failed -> {
                        errors++
                        updates.add(
                            s.copy(
                                lastCheckedAt = now,
                                errorCount = s.errorCount + 1,
                                lastError = o.reason
                            )
                        )
                    }

                    is Outcome.Ok -> {
                        val snap = o.snapshot
                        val nowLive = snap.state == LiveState.LIVE
                        val sessionKey = snap.liveStartAt
                        val transition = evaluateLiveTransition(
                            wasLive = s.isLive,
                            previousStreak = s.liveStreak,
                            previousSessionKey = s.liveStartAt,
                            sessionKey = sessionKey,
                            lastNotifiedLiveStart = s.lastNotifiedLiveStart,
                            nowLive = nowLive,
                            confirmTimes = cfg.confirmTimes
                        )
                        val cooldownOk = PollSchedule.cooldownPassed(
                            lastNotifiedAt = s.lastNotifiedAt,
                            now = now,
                            cooldownMin = cfg.cooldownMin
                        )
                        // 免打扰时段主动抑制：这场就不提醒了，也不排队补发。
                        // 否则安静时段一结束就会突然弹一条深夜开播的通知，而这正是用户
                        // 打开免打扰要避免的——和改造前的行为保持一致。
                        val suppressed = quiet && cfg.quietNoNotify
                        val pending = PendingNotify.check(
                            pendingSession = s.pendingNotifySession,
                            pendingSince = s.pendingNotifySince,
                            sessionKey = sessionKey,
                            now = now
                        )
                        val wantsFresh = transition.notificationCandidate && cooldownOk && !suppressed
                        // 上一轮没发出去（通知总开关 / 渠道被关）：这一轮接着试
                        val wantsRetry = !wantsFresh && !suppressed && cooldownOk &&
                            transition.confirmedLive && pending == PendingNotify.Verdict.RETRY

                        // 默认清空待办：下播、换场、过期、被免打扰抑制都不该再补。
                        // 需要保留时在下面回填，避免每条分支都写一遍清空逻辑。
                        var updated = s.copy(
                            nickname = snap.nickname.ifBlank { s.nickname },
                            avatarUrl = snap.avatarUrl ?: s.avatarUrl,
                            title = snap.title.ifBlank { s.title },
                            // 平台附注跟着最新一次检测走；本次没给就清空，避免残留旧状态
                            note = snap.note?.takeIf { it.isNotBlank() } ?: "",
                            roomUrl = snap.roomUrl,
                            displayId = snap.displayId,
                            isLive = transition.confirmedLive,
                            liveStreak = transition.streak,
                            // 本场开播时刻只在直播中有值；未开播清零
                            liveStartAt = if (nowLive) sessionKey else 0L,
                            // 「上次开播」永久保留：斗鱼接口在未开播时仍返回上一场 start_time
                            lastLiveStartAt = if (sessionKey > 0L) sessionKey else s.lastLiveStartAt,
                            // 检测到 开播 → 未开播 跳变时，记下下播时刻（此刻）
                            lastLiveEndAt = if (!nowLive && s.isLive) now else s.lastLiveEndAt,
                            pendingNotifySession = 0L,
                            pendingNotifySince = 0L,
                            lastCheckedAt = now,
                            missing = false,
                            errorCount = 0,
                            lastError = null
                        )

                        if (transition.confirmedLive && (wantsFresh || wantsRetry)) {
                            val lateByMs = if (wantsRetry) {
                                (now - s.pendingNotifySince).coerceAtLeast(0L)
                            } else {
                                0L
                            }
                            if (NotificationHelper.notifyLive(context, updated, snap, lateByMs)) {
                                updated = updated.copy(
                                    lastNotifiedAt = now,
                                    lastNotifiedLiveStart = sessionKey
                                )
                                notified++
                            } else {
                                // 发不出去（权限或渠道被关）就把这一场记成待办。
                                // 有效期从**第一次**失败算起：反复失败只刷新记录、不续期，
                                // 否则用户一直不开权限，这条待办会永远不过期。
                                updated = updated.copy(
                                    pendingNotifySession =
                                        if (wantsRetry) s.pendingNotifySession else sessionKey,
                                    pendingNotifySince =
                                        if (wantsRetry) s.pendingNotifySince else now
                                )
                            }
                            // 通知结果必须立刻落库：晚一步进程被杀，下一轮就会重复提醒、
                            // 或者（失败时没记下来）从此漏掉这一场。
                            try {
                                withContext(NonCancellable) { dao.update(updated) }
                            } catch (t: Throwable) {
                                Log.e(TAG, "persist notify state failed for id=${s.id}", t)
                            }
                        } else if (!suppressed && transition.confirmedLive &&
                            pending == PendingNotify.Verdict.RETRY
                        ) {
                            // 冷却还没过 / 还没到确认次数：待办留着，下一轮再试
                            updated = updated.copy(
                                pendingNotifySession = s.pendingNotifySession,
                                pendingNotifySince = s.pendingNotifySince
                            )
                        }
                        updates.add(updated)
                    }
                }
            }
        }

        if (updates.isNotEmpty()) dao.updateAll(updates)
        lastRunAt = System.currentTimeMillis()
        lastDurationMs = (lastRunAt - now).coerceAtLeast(0L)
        return Report(
            checked = all.size,
            notified = notified,
            errors = errors,
            pending = updates.count { it.pendingNotifySince > 0L },
            total = all.size,
            startedAt = now,
            finishedAt = lastRunAt
        )
    }

    /**
     * 计算下一次轮询的间隔。
     * 算法在 [PollSchedule] 里，设置页展示的「实际每 X 分钟」走的是同一段代码。
     */
    suspend fun nextDelayMs(): Long {
        val cfg = settings.current()
        return PollSchedule.plan(
            cfg = cfg,
            network = networkKind(),
            quiet = inQuietWindow(cfg, System.currentTimeMillis())
        ).effectiveMs
    }

    /**
     * 当前网络类型（设置页状态卡用）。判定细节集中在 [NetworkState]：
     * 判断不出来时返回 OTHER——「仅 Wi-Fi」会因此跳过本轮，
     * 宁可少查一次，也不要在用户以为不耗流量时偷偷联网。
     */
    fun networkKind(): NetworkKind = NetworkState.read(context).kind

    /** 当前网络能不能真的上网（已通过系统连通性验证） */
    fun netStatus(): NetStatus = NetworkState.read(context).status

    /** 当前是否处于免打扰时段（仅按小时/分钟判断，不看日期） */
    fun inQuietWindow(cfg: Settings, now: Long): Boolean {
        if (!cfg.quietEnabled) return false
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return PollSchedule.inQuietWindow(cfg.quietStartMin, cfg.quietEndMin, minuteOfDay)
    }

    companion object {
        private const val TAG = "MonitorEngine"

        /**
         * 纯函数：此刻的网络是否满足检测条件。
         * 主页状态行与 [runOnce] 共用，保证界面说的和后台做的是同一套判据、同一个优先级
         * （「仅 Wi-Fi」是用户自己的选择，优先于「没有网络」这种故障说明）。
         */
        fun skipReason(wifiOnly: Boolean, net: NetSnapshot): SkipReason? = when {
            wifiOnly && net.kind != NetworkKind.WIFI -> SkipReason.WIFI_ONLY
            !net.online -> SkipReason.NO_NETWORK
            else -> null
        }
    }
}
