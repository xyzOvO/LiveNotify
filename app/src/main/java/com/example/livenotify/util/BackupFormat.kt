package com.example.livenotify.util

import com.example.livenotify.data.Settings
import com.example.livenotify.data.StreamerEntity

/**
 * 关注列表备份格式（纯本地 JSON，不上传）。
 *
 * 刻意只存「用户意图」，不存运行时状态：
 *   - 存：平台 ID、主播 ID、该主播是否被暂停、少量用户设置
 *   - 不存：Cookie、昵称/头像/标题（导入时重新拉）、开播状态、上次开播、
 *           错误计数、通知冷却与已通知场次（这些是本机运行产物，带过去只会误报）
 *
 * 导入时必须重新向平台取一次状态并初始化通知基准，见 MainViewModel.importBackup。
 *
 * 编解码是手写的极简 JSON：项目不想为一份备份文件引入 JSON 库，
 * 而且纯 JVM 实现让单元测试能真正跑编解码（Android 的 org.json 在单测里是 stub）。
 */
object BackupFormat {
    const val VERSION = 1
    const val MIME = "application/json"

    data class Entry(
        val platformId: String,
        val anchorId: String,
        val enabled: Boolean
    )

    data class Prefs(
        val pollIntervalSec: Int,
        val mobileFactor: Int,
        val wifiOnly: Boolean,
        val quietEnabled: Boolean,
        val quietStartMin: Int,
        val quietEndMin: Int,
        val quietNoNotify: Boolean,
        val confirmTimes: Int,
        val cooldownMin: Int,
        val monitorEnabled: Boolean,
        val notifySound: Boolean,
        val notifyVibrate: Boolean,
        val hideMonitorNotification: Boolean,
        val autoUpdateCheck: Boolean
    ) {
        /** 逐项写回 DataStore；Cookie 不在其中，用户得在新设备上自己填 */
        suspend fun applyTo(repo: com.example.livenotify.data.SettingsRepository) {
            repo.setPollInterval(pollIntervalSec)
            repo.setMobileFactor(mobileFactor)
            repo.setWifiOnly(wifiOnly)
            repo.setQuiet(quietEnabled, quietStartMin, quietEndMin, quietNoNotify)
            repo.setConfirmTimes(confirmTimes)
            repo.setCooldownMin(cooldownMin)
            repo.setMonitorEnabled(monitorEnabled)
            repo.setNotifySound(notifySound)
            repo.setNotifyVibrate(notifyVibrate)
            repo.setHideMonitorNotification(hideMonitorNotification)
            repo.setAutoUpdateCheck(autoUpdateCheck)
        }
    }

    data class Data(
        val version: Int = VERSION,
        val exportedAt: Long,
        val entries: List<Entry>,
        val prefs: Prefs
    )

    /** 从当前数据库与设置生成备份内容：白名单在这里收口，避免手滑带上 Cookie/运行状态 */
    fun snapshotOf(all: List<StreamerEntity>, s: Settings, now: Long): Data = Data(
        exportedAt = now,
        entries = all.map { Entry(it.platformId, it.anchorId, it.enabled) },
        prefs = Prefs(
            pollIntervalSec = s.pollIntervalSec,
            mobileFactor = s.mobileFactor,
            wifiOnly = s.wifiOnly,
            quietEnabled = s.quietEnabled,
            quietStartMin = s.quietStartMin,
            quietEndMin = s.quietEndMin,
            quietNoNotify = s.quietNoNotify,
            confirmTimes = s.confirmTimes,
            cooldownMin = s.cooldownMin,
            monitorEnabled = s.monitorEnabled,
            notifySound = s.notifySound,
            notifyVibrate = s.notifyVibrate,
            hideMonitorNotification = s.hideMonitorNotification,
            autoUpdateCheck = s.autoUpdateCheck
        )
    )

    fun encode(d: Data): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"app\": \"LiveNotify\",\n")
        sb.append("  \"version\": ").append(d.version).append(",\n")
        sb.append("  \"exportedAt\": ").append(d.exportedAt).append(",\n")
        sb.append("  \"prefs\": {\n")
        val p = d.prefs
        val prefPairs = listOf(
            "pollIntervalSec" to p.pollIntervalSec.toString(),
            "mobileFactor" to p.mobileFactor.toString(),
            "wifiOnly" to p.wifiOnly.toString(),
            "quietEnabled" to p.quietEnabled.toString(),
            "quietStartMin" to p.quietStartMin.toString(),
            "quietEndMin" to p.quietEndMin.toString(),
            "quietNoNotify" to p.quietNoNotify.toString(),
            "confirmTimes" to p.confirmTimes.toString(),
            "cooldownMin" to p.cooldownMin.toString(),
            "monitorEnabled" to p.monitorEnabled.toString(),
            "notifySound" to p.notifySound.toString(),
            "notifyVibrate" to p.notifyVibrate.toString(),
            "hideMonitorNotification" to p.hideMonitorNotification.toString(),
            "autoUpdateCheck" to p.autoUpdateCheck.toString()
        )
        prefPairs.forEachIndexed { i, (k, v) ->
            sb.append("    ").append(quote(k)).append(": ").append(v)
            if (i != prefPairs.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  },\n")
        sb.append("  \"streamers\": [\n")
        d.entries.forEachIndexed { i, e ->
            sb.append("    { \"platformId\": ").append(quote(e.platformId))
                .append(", \"anchorId\": ").append(quote(e.anchorId))
                .append(", \"enabled\": ").append(e.enabled.toString()).append(" }")
            if (i != d.entries.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n")
        sb.append('}')
        return sb.toString()
    }

    class BadBackup(message: String) : IllegalArgumentException(message)

    /**
     * 解析外部文件：任何字段缺失都退回默认值，单条坏数据跳过而不是让整份备份废掉。
     * 文件来源不可信，先验 app 标记与版本，防止把别的应用的 JSON 当成备份。
     */
    fun decode(text: String): Data {
        val root = try {
            MiniJson.parse(text)
        } catch (t: Throwable) {
            throw BadBackup("这不是 LiveNotify 的备份文件（JSON 解析失败）")
        } as? Map<*, *> ?: throw BadBackup("这不是 LiveNotify 的备份文件")
        if (str(root["app"]) != "LiveNotify") {
            throw BadBackup("这不是 LiveNotify 的备份文件")
        }
        val version = int(root["version"], 0)
        if (version < 1 || version > VERSION) {
            throw BadBackup("备份版本 v$version 不兼容（当前支持 v$VERSION）")
        }
        val arr = root["streamers"] as? List<*> ?: emptyList<Any?>()
        val seen = HashSet<String>()
        val entries = ArrayList<Entry>()
        for (raw in arr) {
            val o = raw as? Map<*, *> ?: continue
            val platform = str(o["platformId"])
            val anchor = str(o["anchorId"])
            if (platform.isBlank() || anchor.isBlank()) continue
            if (!seen.add("$platform:$anchor")) continue
            entries.add(Entry(platform, anchor, bool(o["enabled"], true)))
        }
        val p = root["prefs"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val defaults = Settings()
        val prefs = Prefs(
            pollIntervalSec = int(p["pollIntervalSec"], defaults.pollIntervalSec),
            mobileFactor = int(p["mobileFactor"], defaults.mobileFactor),
            wifiOnly = bool(p["wifiOnly"], defaults.wifiOnly),
            quietEnabled = bool(p["quietEnabled"], defaults.quietEnabled),
            quietStartMin = int(p["quietStartMin"], defaults.quietStartMin),
            quietEndMin = int(p["quietEndMin"], defaults.quietEndMin),
            quietNoNotify = bool(p["quietNoNotify"], defaults.quietNoNotify),
            confirmTimes = int(p["confirmTimes"], defaults.confirmTimes),
            cooldownMin = int(p["cooldownMin"], defaults.cooldownMin),
            monitorEnabled = bool(p["monitorEnabled"], defaults.monitorEnabled),
            notifySound = bool(p["notifySound"], defaults.notifySound),
            notifyVibrate = bool(p["notifyVibrate"], defaults.notifyVibrate),
            hideMonitorNotification = bool(
                p["hideMonitorNotification"], defaults.hideMonitorNotification
            ),
            autoUpdateCheck = bool(p["autoUpdateCheck"], defaults.autoUpdateCheck)
        )
        return Data(version, long(root["exportedAt"], 0L), entries, prefs)
    }

    // 取值时容忍类型不符与 null；备份可能被手工编辑过
    private fun str(v: Any?): String = (v as? String) ?: ""
    private fun int(v: Any?, fallback: Int): Int = when (v) {
        is Number -> v.toInt()
        else -> fallback
    }
    private fun long(v: Any?, fallback: Long): Long = when (v) {
        is Number -> v.toLong()
        else -> fallback
    }
    private fun bool(v: Any?, fallback: Boolean): Boolean = when (v) {
        is Boolean -> v
        else -> fallback
    }

    private fun quote(s: String): String = MiniJson.quote(s)
}
