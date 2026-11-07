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
    val publishedAt: String,
    /** GitHub asset 自带的 sha256:<hex>；302 兜底路径拿不到，为 null 时跳过文件校验 */
    val sha256: String? = null
)

object UpdateApi {

    private const val TAG = "UpdateApi"

    private const val OWNER = "xyzOvO"

    private const val REPO = "LiveNotify"

    private const val LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 项目主页，设置页「关于」里展示并可点击打开 */
    const val PROJECT_PAGE = "https://github.com/$OWNER/$REPO"

    /** 仓库 Releases 页，供手动下载兜底 */
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    private val limiter = RateLimiter(5_000L)

    /** CI 产出的固定附件名，优先选它 */
    private const val PREFERRED_ASSET = "app-release.apk"

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
            publishedAt = root.text("published_at"),
            sha256 = ApkHash.normalizeDigest(apk.text("digest"))
        )
    }

    /** 兜底：只看 Releases 页的重定向目标拿 tag，更新说明再去 Atom 源补一次 */
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
            notes = fetchNotes(tag),
            publishedAt = ""
        )
    }

    /**
     * API 被限流 / 屏蔽时（实测部分网络直接 403），仍尽量把更新日志给到用户。
     * 拿不到就退回空串，界面会显示「暂无更新说明」——不能因为这个失败拖垮整个检查。
     */
    private suspend fun fetchNotes(tag: String): String = try {
        val xml = Http.get("https://github.com/$OWNER/$REPO/releases.atom", limiter = limiter)
        ReleasesAtom.notesForTag(xml, tag)
    } catch (t: Throwable) {
        Log.w(TAG, "fetch release notes from atom failed", t)
        ""
    }

    /**
     * 从 assets 里挑安装包：优先固定名字 `app-release.apk`（CI 产物名），
     * 再退回任意 .apk。Release 里同时挂了多个包（如历史版本、调试包）时，
     * 「第一个 .apk」并不稳定，可能装到别的包上。
     */
    private fun pickApk(assets: JSONArray): JSONObject? {
        var fallback: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val n = a.text("name")
            if (!n.endsWith(".apk", ignoreCase = true)) continue
            if (a.text("browser_download_url").isBlank()) continue
            if (n.equals(PREFERRED_ASSET, ignoreCase = true)) return a
            if (fallback == null) fallback = a
        }
        return fallback
    }
}

/**
 * org.json 的 optString 在字段值为 JSON null 时返回字符串 "null"，不是空串。
 * GitHub 的 release body 允许为空，直接透传会让界面出现一个 "null"。
 */
private fun JSONObject.text(key: String): String =
    optString(key, "").let { if (it == "null") "" else it }
