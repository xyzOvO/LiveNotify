package com.example.livenotify.monitor

import android.util.Log
import com.example.livenotify.data.AppLogDao
import com.example.livenotify.data.AppLogEntity

/**
 * 运行日志收口：写 logcat 的同时落一条到 Room 日志表（脱敏后）。
 *
 * 写入失败一律吞掉——诊断日志绝不能反过来拖垮检测/通知主流程。
 */
internal object AppLogger {
    const val CAT_DETECT = "DETECT"
    const val CAT_NOTIFY = "NOTIFY"
    const val CAT_SERVICE = "SERVICE"
    const val CAT_NETWORK = "NETWORK"

    const val SEV_INFO = "INFO"
    const val SEV_WARN = "WARN"
    const val SEV_ERROR = "ERROR"

    /**
     * 脱敏：去掉 URL 里的查询串（可能带 token），并把连续数字串（主播号/手机号）替换为 #。
     * 主播昵称本身不写进 message（调用方约定），这里再兜一道数字与 URL 参数。
     */
    fun redact(raw: String): String = raw
        .replace(Regex("""https?://[^\s]+?\?[^\s]*""")) { it.value.substringBefore('?') + "?<redacted>" }
        .replace(Regex("""\d{5,}"""), "#")

    /** 落一条日志（同步入口，调用方在协程里用 withContext(IO) 或直接 fire-and-forget） */
    suspend fun record(
        dao: AppLogDao,
        category: String,
        severity: String,
        message: String,
        anchorId: String? = null,
        platformId: String? = null
    ) {
        runCatching {
            dao.insert(
                AppLogEntity(
                    atMs = System.currentTimeMillis(),
                    category = category,
                    severity = severity,
                    message = redact(message),
                    anchorId = anchorId?.let { redact(it) },
                    platformId = platformId
                )
            )
        }
    }

    /** 保留最近 7 天、最多 5000 条；在每轮检测末尾调用 */
    suspend fun maintain(dao: AppLogDao, now: Long) {
        runCatching {
            dao.deleteOlderThan(now - 7L * 24 * 60 * 60 * 1000)
            if (dao.count() > 5000) dao.trimTo(5000)
        }
    }

    /** 同时写 logcat 的便捷方法（ severity 映射到 Log.i/w/e） */
    suspend fun log(
        dao: AppLogDao,
        tag: String,
        category: String,
        severity: String,
        message: String,
        anchorId: String? = null,
        platformId: String? = null
    ) {
        when (severity) {
            SEV_ERROR -> Log.e(tag, message)
            SEV_WARN -> Log.w(tag, message)
            else -> Log.i(tag, message)
        }
        record(dao, category, severity, message, anchorId, platformId)
    }
}
