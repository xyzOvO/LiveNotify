package com.example.livenotify.platform

import com.example.livenotify.net.Http
import com.example.livenotify.net.RateLimiter
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 斗鱼：使用 web 端公开的房间信息接口 `open.douyucdn.cn/api/RoomApi/room/{rid}`。
 *
 * 该接口免登录、无需 Cookie，返回 `room_status`（1=直播中 / 2=未开播）与 `start_time`
 * （**本场或上一场的开播时刻**，未开播时保留上一次的值）——后者是"距上次开播"功能的数据源。
 *
 * 限制：
 * - 不支持批量查询，只能逐个请求，因此节流比其他平台更保守（1.2 秒/次）；
 * - 属 web 公开接口而非签约开放平台，字段若变更需重新适配；解析失败一律报错，不猜状态。
 */
object DouyuPlatform : LivePlatform {

    override val id = "douyu"
    override val displayName = "斗鱼"
    override val usesOfficialApi = false
    override val degradeNote =
        "使用斗鱼 web 端公开房间接口（免登录），无批量能力，已按 1.2 秒/次节流。接口字段变更会导致失效。"
    override val batchSize = 1
    override val minRequestGapMs = 1200L

    private const val API_ROOM = "https://open.douyucdn.cn/api/RoomApi/room/"

    private val limiter = RateLimiter(minRequestGapMs)

    private val timeZone = TimeZone.getTimeZone("GMT+8")

    override fun accept(raw: String): Boolean {
        val s = raw.trim()
        return s.contains("douyu.com", true) || s.contains("douyucdn.cn", true)
    }

    override suspend fun resolve(raw: String): String? {
        val s = raw.trim()
        if (s.matches(Regex("^\\d{1,10}$"))) return s      // 手动指定平台时直接给房间号
        if (!s.startsWith("http", true)) return null

        // https://www.douyu.com/9999 、 https://m.douyu.com/9999 、 ?rid=9999
        Regex("""douyu\.com/(?:room/)?(\d{1,10})(?:[/?#]|$)""").find(s)
            ?.groupValues?.get(1)?.let { return it }
        Regex("""[?&]rid=(\d{1,10})""").find(s)
            ?.groupValues?.get(1)?.let { return it }

        // 拼音房间号（如 douyu.com/yyf）：取跳转后的真实地址再解析
        val finalUrl = Http.finalUrl(s, limiter) ?: return null
        Regex("""douyu\.com/(\d{1,10})(?:[/?#]|$)""").find(finalUrl)
            ?.groupValues?.get(1)?.let { return it }

        // 兜底：页面里常见 "room_id":12345 或 rid=12345
        val html = try {
            Http.get(s, mapOf("Referer" to "https://www.douyu.com/"), null, limiter)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        } ?: return null
        Regex(""""room_id"\s*:\s*"?(\d{1,10})""").find(html)
            ?.groupValues?.get(1)?.let { return it }
        Regex("""roomId\s*[:=]\s*(\d{1,10})""").find(html)
            ?.groupValues?.get(1)?.let { return it }
        return null
    }

    override suspend fun fetch(anchorIds: List<String>): List<Outcome> {
        val out = ArrayList<Outcome>(anchorIds.size)
        for (rid in anchorIds) {
            out.add(fetchOne(rid))
        }
        return out
    }

    private suspend fun fetchOne(rid: String): Outcome {
        val body = try {
            Http.get(
                "$API_ROOM$rid",
                mapOf("Referer" to "https://www.douyu.com/", "Accept" to "application/json, text/plain, */*"),
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
            return Outcome.Failed(rid, "斗鱼接口返回非 JSON，可能被风控或接口已变更", retryable = false)
        }

        val err = root.optInt("error", -1)
        if (err != 0) {
            // error:101 房间不存在/已关闭；其余按接口异常处理
            return if (err == 101) Outcome.Missing(rid, "房间不存在或已关闭")
            else Outcome.Failed(rid, "斗鱼接口 error=$err", retryable = true)
        }

        val data = root.optJSONObject("data")
            ?: return Outcome.Failed(rid, "斗鱼接口缺少 data 字段", retryable = false)

        val status = data.optString("room_status", "")
        if (status != "1" && status != "2") {
            return Outcome.Failed(rid, "斗鱼返回未知的 room_status=$status", retryable = false)
        }

        val roomId = data.optString("room_id", rid).ifBlank { rid }
        val liveStartSec = parseTime(data.optString("start_time", ""))

        return Outcome.Ok(
            rid,
            Snapshot(
                anchorId = rid,
                nickname = unescape(data.optString("owner_name", "")).ifBlank { "斗鱼主播 $roomId" },
                avatarUrl = data.optString("avatar", "").takeIf { it.isNotBlank() },
                title = unescape(data.optString("room_name", "")),
                state = if (status == "1") LiveState.LIVE else LiveState.OFF,
                liveStartAt = liveStartSec,
                roomUrl = "https://www.douyu.com/$roomId",
                displayId = roomId,
                note = data.optString("cate_name", "").takeIf { it.isNotBlank() }
            )
        )
    }

    /** "2026-10-28 23:26:56" (GMT+8) → 秒级时间戳；解析失败返回 0 */
    private fun parseTime(raw: String): Long {
        if (raw.isBlank()) return 0L
        return try {
            // SimpleDateFormat 非线程安全，新建一个实例供本次解析使用。
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .apply { timeZone = this@DouyuPlatform.timeZone }
                .parse(raw)?.time?.div(1000L) ?: 0L
        } catch (t: Throwable) {
            0L
        }
    }

    private fun unescape(s: String): String =
        s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()

    override fun fallbackUrl(anchorId: String): String = "https://www.douyu.com/$anchorId"
}
