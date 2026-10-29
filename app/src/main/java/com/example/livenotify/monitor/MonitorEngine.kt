package com.example.livenotify.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.livenotify.data.AppDatabase
import com.example.livenotify.data.Settings
import com.example.livenotify.data.SettingsRepository
import com.example.livenotify.data.StreamerEntity
import com.example.livenotify.platform.LiveState
import com.example.livenotify.platform.Outcome
import com.example.livenotify.platform.PlatformRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

/**
 * 检测引擎：一次轮询 = 按平台分组 → 批量拉取 → 状态对比 → 去重 → 推送 → 落库。
 *
 * 防误报 / 防重复推送的三道闸：
 *   1. 状态跳变：必须「上次未开播 → 本次开播」才可能推送；
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
        val skipped: Boolean = false,
        val finishedAt: Long = 0L
    )

    @Volatile
    var lastRunAt: Long = 0L
        private set

    /** 后台轮询和手动刷新共用同一状态机，必须串行执行，避免重复通知和旧快照覆盖新结果。 */
    private val runMutex = Mutex()

    suspend fun runOnce(): Report = runMutex.withLock {
        val cfg = settings.current()
        // 抖音降级路径需要用户自备 Cookie（可选）
        com.example.livenotify.platform.DouyinPlatform.cookie = cfg.douyinCookie

        val now = System.currentTimeMillis()
        val dao = db.streamerDao()
        val all = dao.listEnabled()
        if (all.isEmpty()) {
            lastRunAt = now
            return Report(finishedAt = now)
        }

        // 仅 WiFi 模式：移动网络下跳过本轮，不产生流量
        if (cfg.wifiOnly && !isOnWifi()) {
            lastRunAt = now
            return Report(skipped = true, finishedAt = now)
        }

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
                        val streak = if (nowLive) s.liveStreak + 1 else 0
                        val sessionKey = snap.liveStartAt
                        val cooldownOk = now - s.lastNotifiedAt >= cfg.cooldownMin * 60_000L
                        val newSession = sessionKey <= 0L || sessionKey != s.lastNotifiedLiveStart

                        // 未达到确认次数前保持 isLive=false，否则第二轮会被 !s.isLive 永久挡住。
                        val confirmedLive = nowLive && (s.isLive || streak >= cfg.confirmTimes)
                        val shouldNotify = nowLive &&
                            !s.isLive &&
                            streak >= cfg.confirmTimes &&
                            cooldownOk &&
                            newSession &&
                            !(quiet && cfg.quietNoNotify)

                        var updated = s.copy(
                            nickname = snap.nickname.ifBlank { s.nickname },
                            avatarUrl = snap.avatarUrl ?: s.avatarUrl,
                            title = snap.title.ifBlank { s.title },
                            roomUrl = snap.roomUrl,
                            displayId = snap.displayId,
                            isLive = confirmedLive,
                            liveStreak = streak,
                            // 本场开播时刻只在直播中有值；未开播清零
                            liveStartAt = if (nowLive) sessionKey else 0L,
                            // 「上次开播」永久保留：斗鱼接口在未开播时仍返回上一场 start_time
                            lastLiveStartAt = if (sessionKey > 0L) sessionKey else s.lastLiveStartAt,
                            // 检测到 开播 → 未开播 跳变时，记下下播时刻（此刻）
                            lastLiveEndAt = if (!nowLive && s.isLive) now else s.lastLiveEndAt,
                            lastCheckedAt = now,
                            missing = false,
                            errorCount = 0,
                            lastError = null
                        )

                        if (shouldNotify && NotificationHelper.notifyLive(context, updated, snap)) {
                            updated = updated.copy(
                                lastNotifiedAt = now,
                                lastNotifiedLiveStart = sessionKey
                            )
                            notified++
                        }
                        updates.add(updated)
                    }
                }
            }
        }

        if (updates.isNotEmpty()) dao.updateAll(updates)
        lastRunAt = System.currentTimeMillis()
        return Report(
            checked = all.size,
            notified = notified,
            errors = errors,
            finishedAt = lastRunAt
        )
    }

    /** 计算下一次轮询的间隔：基础间隔 × 移动网络倍数，免打扰时段进一步降频 */
    suspend fun nextDelayMs(): Long {
        val cfg = settings.current()
        var delay = cfg.pollIntervalSec * 1000L
        if (isOnCellular()) delay *= cfg.mobileFactor.coerceAtLeast(1)
        if (inQuietWindow(cfg, System.currentTimeMillis())) delay *= 5
        return delay.coerceIn(30_000L, 30 * 60_000L)
    }

    private fun hasTransport(transport: Int): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(transport)
    }

    /** 无法读取网络信息时保守返回 false，让「仅 WiFi」跳过本轮，而不是放行未知网络。 */
    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun isOnCellular(): Boolean = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

    private fun inQuietWindow(cfg: Settings, now: Long): Boolean {
        if (!cfg.quietEnabled) return false
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (cfg.quietStartMin <= cfg.quietEndMin) {
            m >= cfg.quietStartMin && m < cfg.quietEndMin
        } else {
            m >= cfg.quietStartMin || m < cfg.quietEndMin
        }
    }

    companion object {
        private const val TAG = "MonitorEngine"
    }
}
