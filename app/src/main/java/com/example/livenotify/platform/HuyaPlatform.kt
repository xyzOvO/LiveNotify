package com.example.livenotify.platform

import com.example.livenotify.net.Http
import com.example.livenotify.net.RateLimiter
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * 虎牙：使用 web 端公开的房间信息接口 `mp.huya.com/cache.php?m=Live&do=profileRoom`。
 *
 * 免登录、返回 JSON：
 * - `data.liveStatus`：`ON` 直播中 / `OFF` 未开播（`realLiveStatus` 同值，用作交叉校验）
 * - `data.profileInfo.nick` / `avatar180`
 * - `data.liveData.introduction`：直播间标题
 * - `status != 200`（如 422「该主播不存在！」）→ 房间失效
 *
 * 该接口不返回开播时间戳，因此虎牙主播的「上次开播」只能靠本地观测累积。
 * 无批量能力，逐个请求并节流。
 */
object HuyaPlatform : LivePlatform {

    override val id = "huya"
    override val displayName = "虎牙"
    override val usesOfficialApi = false
    override val degradeNote =
        "使用虎牙 web 端公开房间接口（免登录、返回 JSON，较稳定），无批量能力，已按 1 秒/次节流。接口不返回开播时间，\u300c上次开播\u300d依赖本地观测累积。"
    override val batchSize = 1
    override val minRequestGapMs = 1000L

    private const val API = "https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid="

    private val limiter = RateLimiter(minRequestGapMs)

    override fun accept(raw: String): Boolean {
        val s = raw.trim()
        return s.contains("huya.com", true)
    }

    override suspend fun resolve(raw: String): String? {
        val s = raw.trim()
        if (s.matches(Regex("^\\d{1,10}$"))) return s
        if (!s.startsWith("http", true)) return null

        Regex("""huya\.com/(?:room/)?(\d{1,10})(?:[/?#]|$)""").find(s)
            ?.groupValues?.get(1)?.let { return it }

        // 拼音/自定义房间号：跟随跳转后取真实房间号
        val finalUrl = Http.finalUrl(s, limiter) ?: return null
        Regex("""huya\.com/(?:room/)?(\d{1,10})(?:[/?#]|$)""").find(finalUrl)
            ?.groupValues?.get(1)?.let { return it }

        // 兜底：页面里的 roomId / lProfileRoom
        val html = try {
            Http.get(s, mapOf("Referer" to "https://www.huya.com/"), null, limiter)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        } ?: return null
        Regex(""""lProfileRoom"\s*:\s*(\d{1,10})""").find(html)
            ?.groupValues?.get(1)?.let { return it }
        Regex("""roomId\s*[:=]\s*"?(\d{1,10})""").find(html)
            ?.groupValues?.get(1)?.let { return it }
        return null
    }

    override suspend fun fetch(anchorIds: List<String>): List<Outcome> {
        val out = ArrayList<Outcome>(anchorIds.size)
        for (rid in anchorIds) out.add(fetchOne(rid))
        return out
    }

    private suspend fun fetchOne(rid: String): Outcome {
        val body = try {
            Http.get(
                "$API$rid",
                mapOf(
                    "Referer" to "https://www.huya.com/",
                    "Accept" to "application/json, text/plain, */*"
                ),
                null,
                limiter
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return Outcome.Failed(rid, t.message ?: "网络异常", retryable = true)
        }

        val root = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return Outcome.Failed(rid, "虎牙接口返回非 JSON，可能被风控或接口已变更", retryable = false)
        }

        val status = root.optInt("status", -1)
        if (status != 200) {
            val msg = root.optString("message", "接口异常")
            return if (status == 422) Outcome.Missing(rid, msg)
            else Outcome.Failed(rid, "虎牙接口 $status：$msg", retryable = true)
        }

        val data = root.optJSONObject("data")
            ?: return Outcome.Failed(rid, "虎牙接口缺少 data 字段", retryable = false)

        val live = data.optString("liveStatus", "").uppercase()
        val real = data.optString("realLiveStatus", "").uppercase()
        if (live != "ON" && live != "OFF") {
            return Outcome.Failed(rid, "虎牙返回未知 liveStatus=$live", retryable = false)
        }
        // 两个字段不一致时保守处理：宁可当作未开播，也不误报开播
        val isLive = live == "ON" && (real.isBlank() || real == "ON")

        val profile = data.optJSONObject("profileInfo")
        val liveData = data.optJSONObject("liveData")
        val intro = liveData?.optString("introduction", "")?.takeIf { it.isNotBlank() }
            ?: liveData?.optString("sIntroduction", "").orEmpty()

        return Outcome.Ok(
            rid,
            Snapshot(
                anchorId = rid,
                nickname = profile?.optString("nick", "").orEmpty().ifBlank { "虎牙主播 $rid" },
                avatarUrl = profile?.optString("avatar180", "")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { normalizeAvatar(it) },
                title = intro,
                state = if (isLive) LiveState.LIVE else LiveState.OFF,
                liveStartAt = 0L,   // 该接口不提供开播时间
                roomUrl = "https://www.huya.com/$rid",
                displayId = rid,
                note = null
            )
        )
    }

    /** 虎牙头像 URL 有时不带扩展名，补 .jpg；已带扩展名（含 query 参数的情况）则保持原样 */
    private fun normalizeAvatar(url: String): String {
        val u = url.trim()
        val full = if (u.startsWith("//")) "https:$u" else u
        val path = full.substringBefore('?')
        val hasExt = Regex("""\.(jpg|jpeg|png|webp|gif)$""", RegexOption.IGNORE_CASE).containsMatchIn(path)
        if (hasExt) return full
        // 扩展名要补在 path 上，不能补到 query 后面（如 ?size=180 会变成 ?size=180.jpg 而失效）。
        val query = full.substringAfter('?', "")
        val withExt = "$path.jpg"
        return if (query.isEmpty()) withExt else "$withExt?$query"
    }

    override fun fallbackUrl(anchorId: String): String = "https://www.huya.com/$anchorId"
}
