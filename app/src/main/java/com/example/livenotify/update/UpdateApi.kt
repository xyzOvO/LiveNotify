package com.example.livenotify.update

import android.util.Log
import com.example.livenotify.net.Http
import com.example.livenotify.net.RateLimiter
import org.json.JSONArray
import org.json.JSONObject

/** GitHub 上的一条发布信息，只取更新用得到的字段 */
data class ReleaseInfo(
    val tagName: String,        // v1.0.4
    val versionName: String,   // 1.0.4
    val versionCode: Long,      // 10004，算法与 build.gradle.kts 的 tagVersion 一致
    val apkUrl: String,
    val assetName: String,
    val sizeBytes: Long,
    val notes: String,          // Release 说明
    val publishedAt: String
)

object UpdateApi {

    private const val TAG = "UpdateApi"

    private const val OWNER = "xyzOvO"

    private const val REPO = "LiveNotify"

    private const val LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 仓库 Releases 页，供手动下载兜底 */
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    private val limiter = RateLimiter(5_000L)

    /**
     * 取最新正式发布。返回 null 表示请求失败、被限流，或该发布下没有 APK 附件。
     * 只认形如 v1.2.3 的 tag，其余一律当作「没有新版本」，避免误报。
     */
    suspend fun latest(): ReleaseInfo? {
        val body: String
        try {
            body = Http.getJson(LATEST, limiter)
        } catch (t: Throwable) {
            Log.w(TAG, "fetch latest release failed", t)
            return null
        }

        val root: JSONObject
        try {
            root = JSONObject(body)
        } catch (t: Throwable) {
            return null
        }

        val tag = root.optString("tag_name", "").trim()
        val m = Regex("""^v(\d+)\.(\d+)\.(\d+)$""").matchEntire(tag)
        if (m == null) return null

        val assets = root.optJSONArray("assets")
        if (assets == null) return null

        val apk = pickApk(assets)
        if (apk == null) return null

        val major = m.groupValues[1].toLong()
        val minor = m.groupValues[2].toLong()
        val patch = m.groupValues[3].toLong()

        val url = apk.optString("browser_download_url", "")
        val name = apk.optString("name", "app-release.apk")
        val size = apk.optLong("size", 0L)

        return ReleaseInfo(
            tagName = tag,
            versionName = "$major.$minor.$patch",
            versionCode = major * 10000L + minor * 100L + patch,
            apkUrl = url,
            assetName = name,
            sizeBytes = size,
            notes = root.optString("body", "").trim(),
            publishedAt = root.optString("published_at", "")
        )
    }

    /** 取 assets 里第一个 .apk 附件 */
    private fun pickApk(assets: JSONArray): JSONObject? {
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val n = a.optString("name", "")
            if (!n.endsWith(".apk", ignoreCase = true)) continue
            if (a.optString("browser_download_url", "").isNotBlank()) return a
        }
        return null
    }
}
