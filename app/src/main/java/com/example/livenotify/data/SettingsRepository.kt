package com.example.livenotify.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.io.IOException

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val pollIntervalSec: Int = 120,       // 默认 2 分钟（1~3 分钟区间）
    val mobileFactor: Int = 3,           // 移动网络下间隔倍数
    val wifiOnly: Boolean = false,       // 仅 WiFi 下检测
    val quietEnabled: Boolean = false,
    val quietStartMin: Int = 23 * 60,    // 免打扰起始（分钟）
    val quietEndMin: Int = 8 * 60,       // 免打扰结束（分钟）
    val quietNoNotify: Boolean = true,   // 免打扰时段内不推送
    val confirmTimes: Int = 1,           // 连续检测到开播的次数才推送（防误报）
    val cooldownMin: Int = 10,           // 同一主播推送冷却（分钟）
    val douyinCookie: String = "",
    val monitorEnabled: Boolean = true,
    /** 开播通知是否响铃（关掉后只弹通知不出声） */
    val notifySound: Boolean = true,
    /** 开播通知是否震动 */
    val notifyVibrate: Boolean = true,
    /** 隐藏后台检测的常驻通知（Android 要求前台服务必须有通知，只能降到最低可见度） */
    val hideMonitorNotification: Boolean = false,
    /** 启动时自动检查一次新版本（只提示，不会自动下载安装） */
    val autoUpdateCheck: Boolean = true,
    /** 上次检查更新的时刻（毫秒）。自动检查按 24 小时间隔节流，避免每次冷启动都打接口 */
    val lastUpdateCheckAt: Long = 0L,
    /** 用户点过「以后再说」的版本；自动检查到这个版本就不再弹，手动检查仍可看到 */
    val ignoredVersionCode: Long = 0L,
    /** 上次成功访问 B 站数据接口的域；空串表示还没记录，走默认主节点 */
    val statsApiOrigin: String = ""
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val INTERVAL = intPreferencesKey("poll_interval_sec")
        val MOBILE_FACTOR = intPreferencesKey("mobile_factor")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val QUIET_ENABLED = booleanPreferencesKey("quiet_enabled")
        val QUIET_START = intPreferencesKey("quiet_start")
        val QUIET_END = intPreferencesKey("quiet_end")
        val QUIET_NO_NOTIFY = booleanPreferencesKey("quiet_no_notify")
        val CONFIRM_TIMES = intPreferencesKey("confirm_times")
        val COOLDOWN_MIN = intPreferencesKey("cooldown_min")
        val DOUYIN_COOKIE = stringPreferencesKey("douyin_cookie")
        val MONITOR_ENABLED = booleanPreferencesKey("monitor_enabled")
        val NOTIFY_SOUND = booleanPreferencesKey("notify_sound")
        val NOTIFY_VIBRATE = booleanPreferencesKey("notify_vibrate")
        val HIDE_MONITOR_NOTIFICATION = booleanPreferencesKey("hide_monitor_notification")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_at")
        val IGNORED_VERSION = longPreferencesKey("ignored_version_code")
        val STATS_API_ORIGIN = stringPreferencesKey("stats_api_origin")
    }

    /** 最近一次成功读到的设置：读盘失败时沿用它，而不是退回「默认值」 */
    @Volatile
    private var lastKnown: Settings? = null

    val settings: Flow<Settings> = context.settingsStore.data
        .map { p ->
            Settings(
                pollIntervalSec = p[Keys.INTERVAL] ?: 120,
                mobileFactor = p[Keys.MOBILE_FACTOR] ?: 3,
                wifiOnly = p[Keys.WIFI_ONLY] ?: false,
                quietEnabled = p[Keys.QUIET_ENABLED] ?: false,
                quietStartMin = p[Keys.QUIET_START] ?: (23 * 60),
                quietEndMin = p[Keys.QUIET_END] ?: (8 * 60),
                quietNoNotify = p[Keys.QUIET_NO_NOTIFY] ?: true,
                confirmTimes = p[Keys.CONFIRM_TIMES] ?: 1,
                cooldownMin = p[Keys.COOLDOWN_MIN] ?: 10,
                // DataStore 里存的是 CookieVault 密文；对外仍暴露明文，DouyinPlatform 管线不用改。
                // decrypt 对旧明文原样返回，天然兼容升级前的配置。
                douyinCookie = CookieVault.decrypt(p[Keys.DOUYIN_COOKIE] ?: ""),
                monitorEnabled = p[Keys.MONITOR_ENABLED] ?: true,
                notifySound = p[Keys.NOTIFY_SOUND] ?: true,
                notifyVibrate = p[Keys.NOTIFY_VIBRATE] ?: true,
                hideMonitorNotification = p[Keys.HIDE_MONITOR_NOTIFICATION] ?: false,
                autoUpdateCheck = p[Keys.AUTO_UPDATE_CHECK] ?: true,
                lastUpdateCheckAt = p[Keys.LAST_UPDATE_CHECK] ?: 0L,
                ignoredVersionCode = p[Keys.IGNORED_VERSION] ?: 0L,
                statsApiOrigin = p[Keys.STATS_API_ORIGIN] ?: ""
            )
        }
        .onEach { lastKnown = it }
        .catch { e ->
            if (e !is IOException) throw e
            // 读盘失败时退回上一次成功读到的值。绝不能退回默认值：默认 monitorEnabled=true，
            // 会让用户明明关掉的监控被重新拉起。
            emit(lastKnown ?: Settings())
        }

    suspend fun current(): Settings = settings.first()

    suspend fun edit(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }

    suspend fun setPollInterval(sec: Int) = edit { it[Keys.INTERVAL] = sec.coerceIn(60, 600) }
    suspend fun setMobileFactor(f: Int) = edit { it[Keys.MOBILE_FACTOR] = f.coerceIn(1, 6) }

    /**
     * 一次性写入整套检测参数（三档预设用）。
     *
     * 必须合并成一次 edit：拆成四次写，界面会先看到「间隔已变、其它还是旧值」的
     * 中间状态，档位判定瞬间变成「自定义」，看起来像点错了。
     * 不动 wifiOnly——那是流量策略，不属于检测节奏。
     */
    suspend fun setDetectProfile(intervalSec: Int, mobileFactor: Int, confirmTimes: Int, cooldownMin: Int) = edit {
        it[Keys.INTERVAL] = intervalSec.coerceIn(60, 600)
        it[Keys.MOBILE_FACTOR] = mobileFactor.coerceIn(1, 6)
        it[Keys.CONFIRM_TIMES] = confirmTimes.coerceIn(1, 3)
        it[Keys.COOLDOWN_MIN] = cooldownMin.coerceIn(0, 120)
    }
    suspend fun setWifiOnly(v: Boolean) = edit { it[Keys.WIFI_ONLY] = v }
    suspend fun setQuiet(enabled: Boolean, startMin: Int, endMin: Int, noNotify: Boolean) = edit {
        it[Keys.QUIET_ENABLED] = enabled
        it[Keys.QUIET_START] = startMin.coerceIn(0, 1439)
        it[Keys.QUIET_END] = endMin.coerceIn(0, 1439)
        it[Keys.QUIET_NO_NOTIFY] = noNotify
    }
    suspend fun setConfirmTimes(n: Int) = edit { it[Keys.CONFIRM_TIMES] = n.coerceIn(1, 3) }
    suspend fun setCooldownMin(n: Int) = edit { it[Keys.COOLDOWN_MIN] = n.coerceIn(0, 120) }
    /** 写入抖音 Cookie：先用 AndroidKeyStore 加密再落盘；空串表示清除。 */
    suspend fun setDouyinCookie(c: String) = edit {
        val trimmed = c.trim()
        if (trimmed.isBlank()) it.remove(Keys.DOUYIN_COOKIE)
        else it[Keys.DOUYIN_COOKIE] = CookieVault.encrypt(trimmed)
    }
    suspend fun setMonitorEnabled(v: Boolean) = edit { it[Keys.MONITOR_ENABLED] = v }
    suspend fun setNotifySound(v: Boolean) = edit { it[Keys.NOTIFY_SOUND] = v }
    suspend fun setNotifyVibrate(v: Boolean) = edit { it[Keys.NOTIFY_VIBRATE] = v }
    suspend fun setHideMonitorNotification(v: Boolean) = edit { it[Keys.HIDE_MONITOR_NOTIFICATION] = v }
    suspend fun setAutoUpdateCheck(v: Boolean) = edit { it[Keys.AUTO_UPDATE_CHECK] = v }

    /**
     * 一次性写入整套「备份里带的设置」。
     *
     * 必须合并成一次 edit：原来 [BackupFormat.Prefs.applyTo] 拆成十几次写，
     * 中途 DataStore 出错就会留下一半恢复一半原样的状态，和「导入失败已回滚」的说法不符。
     * 刻意不写 Cookie/最近检查时间/已忽略版本/接口域——这些是本机状态，不进备份。
     * 拆出这个方法后，[BackupFormat.Prefs.applyTo] 只做一层薄封装，调用方仍能复用。
     */
    suspend fun applyBackupPrefs(p: com.example.livenotify.util.BackupFormat.Prefs) = edit {
        it[Keys.INTERVAL] = p.pollIntervalSec.coerceIn(60, 600)
        it[Keys.MOBILE_FACTOR] = p.mobileFactor.coerceIn(1, 6)
        it[Keys.WIFI_ONLY] = p.wifiOnly
        it[Keys.QUIET_ENABLED] = p.quietEnabled
        it[Keys.QUIET_START] = p.quietStartMin.coerceIn(0, 1439)
        it[Keys.QUIET_END] = p.quietEndMin.coerceIn(0, 1439)
        it[Keys.QUIET_NO_NOTIFY] = p.quietNoNotify
        it[Keys.CONFIRM_TIMES] = p.confirmTimes.coerceIn(1, 3)
        it[Keys.COOLDOWN_MIN] = p.cooldownMin.coerceIn(0, 120)
        it[Keys.MONITOR_ENABLED] = p.monitorEnabled
        it[Keys.NOTIFY_SOUND] = p.notifySound
        it[Keys.NOTIFY_VIBRATE] = p.notifyVibrate
        it[Keys.HIDE_MONITOR_NOTIFICATION] = p.hideMonitorNotification
        it[Keys.AUTO_UPDATE_CHECK] = p.autoUpdateCheck
    }

    /** 记下「本次检查更新的时刻」；自动检查据此做 24 小时节流，手动检查也会刷新它 */
    suspend fun setLastUpdateCheckAt(ms: Long) = edit { it[Keys.LAST_UPDATE_CHECK] = ms }

    /** 记下用户不想再被提醒的版本（传 0 表示恢复提醒） */
    suspend fun setIgnoredVersionCode(v: Long) = edit {
        if (v <= 0L) it.remove(Keys.IGNORED_VERSION) else it[Keys.IGNORED_VERSION] = v
    }

    /** 记住最近一次成功的数据接口域；传空串即清掉，下次重新从主节点开始试 */
    suspend fun setStatsApiOrigin(origin: String) = edit {
        if (origin.isBlank()) it.remove(Keys.STATS_API_ORIGIN)
        else it[Keys.STATS_API_ORIGIN] = origin
    }
}
