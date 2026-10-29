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
     *
     * api.github.com 在部分网络下会被限流或连不上，此时退一步走 Releases 页：
     * /releases/latest 会 302 到 /releases/tag/vX.Y.Z，拿到 tag 就能拼出下载地址。
     */
    suspend fun latest(): ReleaseInfo? = fromApi() ?: fromHtml()

    private suspend fun fromApi(): ReleaseInfo? {
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

        val tag = root.text("tag_name").trim()
        val m = Regex("""^v(\d+)\.(\d+)\.(\d+)$""").matchEntire(tag)
        if (m == null) return null

        val assets = root.optJSONArray("assets")
        if (assets == null) return null

        val apk = pickApk(assets)
        if (apk == null) return null

        val major = m.groupValues[1].toLong()
        val minor = m.groupValues[2].toLong()
        val patch = m.groupValues[3].toLong()

        val url = apk.text("browser_download_url")
        val name = apk.text("name").ifBlank { "app-release.apk" }
        val size = apk.optLong("size", 0L)

        return ReleaseInfo(
            tagName = tag,
            versionName = "$major.$minor.$patch",
            versionCode = major * 10000L + minor * 100L + patch,
            apkUrl = url,
            assetName = name,
            sizeBytes = size,
            notes = root.text("body").trim(),
            publishedAt = root.text("published_at")
        )
    }

    /** 兜底：只看 Releases 页的重定向目标，拿不到更新说明 */
    private suspend fun fromHtml(): ReleaseInfo? {
        val finalUrl = Http.finalUrl("$RELEASES_PAGE/latest", limiter) ?: return null
        val tag = Regex("""/releases/tag/(v\d+\.\d+\.\d+)$""").find(finalUrl.trimEnd('/'))?.groupValues?.get(1)
            ?: return null
        val m = Regex("""^v(\d+)\.(\d+)\.(\d+)$""").matchEntire(tag) ?: return null
        val major = m.groupValues[1].toLong()
        val minor = m.groupValues[2].toLong()
        val patch = m.groupValues[3].toLong()
        val name = "app-release.apk"
        return ReleaseInfo(
            tagName = tag,
            versionName = "$major.$minor.$patch",
            versionCode = major * 10000L + minor * 100L + patch,
            apkUrl = "https://github.com/$OWNER/$REPO/releases/download/$tag/$name",
            assetName = name,
            sizeBytes = 0L,
            notes = "",
            publishedAt = ""
        )
    }

    /** 取 assets 里第一个 .apk 附件 */
    private fun pickApk(assets: JSONArray): JSONObject? {
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val n = a.text("name")
            if (!n.endsWith(".apk", ignoreCase = true)) continue
            if (a.text("browser_download_url").isNotBlank()) return a
        }
        return null
    }
}

/**
 * org.json 的 optString 在字段值为 JSON null 时返回字符串 "null"，不是空串。
 * GitHub 的 release body 允许为空，直接透传会让界面出现一个 "null"。
 */
private fun JSONObject.text(key: String): String =
    optString(key, "").let { if (it == "null") "" else it }
