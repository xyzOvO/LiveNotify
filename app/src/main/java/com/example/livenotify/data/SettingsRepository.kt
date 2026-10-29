package com.example.livenotify.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    val hideMonitorNotification: Boolean = false
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
    }

    val settings: Flow<Settings> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
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
                douyinCookie = p[Keys.DOUYIN_COOKIE] ?: "",
                monitorEnabled = p[Keys.MONITOR_ENABLED] ?: true,
                notifySound = p[Keys.NOTIFY_SOUND] ?: true,
                notifyVibrate = p[Keys.NOTIFY_VIBRATE] ?: true,
                hideMonitorNotification = p[Keys.HIDE_MONITOR_NOTIFICATION] ?: false
            )
        }

    suspend fun current(): Settings = settings.first()

    suspend fun edit(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }

    suspend fun setPollInterval(sec: Int) = edit { it[Keys.INTERVAL] = sec.coerceIn(60, 600) }
    suspend fun setMobileFactor(f: Int) = edit { it[Keys.MOBILE_FACTOR] = f.coerceIn(1, 6) }
    suspend fun setWifiOnly(v: Boolean) = edit { it[Keys.WIFI_ONLY] = v }
    suspend fun setQuiet(enabled: Boolean, startMin: Int, endMin: Int, noNotify: Boolean) = edit {
        it[Keys.QUIET_ENABLED] = enabled
        it[Keys.QUIET_START] = startMin.coerceIn(0, 1439)
        it[Keys.QUIET_END] = endMin.coerceIn(0, 1439)
        it[Keys.QUIET_NO_NOTIFY] = noNotify
    }
    suspend fun setConfirmTimes(n: Int) = edit { it[Keys.CONFIRM_TIMES] = n.coerceIn(1, 3) }
    suspend fun setCooldownMin(n: Int) = edit { it[Keys.COOLDOWN_MIN] = n.coerceIn(0, 120) }
    suspend fun setDouyinCookie(c: String) = edit { it[Keys.DOUYIN_COOKIE] = c.trim() }
    suspend fun setMonitorEnabled(v: Boolean) = edit { it[Keys.MONITOR_ENABLED] = v }
    suspend fun setNotifySound(v: Boolean) = edit { it[Keys.NOTIFY_SOUND] = v }
    suspend fun setNotifyVibrate(v: Boolean) = edit { it[Keys.NOTIFY_VIBRATE] = v }
    suspend fun setHideMonitorNotification(v: Boolean) = edit { it[Keys.HIDE_MONITOR_NOTIFICATION] = v }
}
