package com.example.livenotify.platform

import com.example.livenotify.net.Http
import com.example.livenotify.net.RateLimiter
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * B 站：使用官方公开直播接口，免登录、无需 Cookie。
 *
 * - 房间号/短号 -> uid：/room/v1/Room/get_info
 * - 批量查询状态：/room/v1/Room/get_status_info_by_uids（一次请求最多查 20 个主播，显著省电省流量）
 *
 * anchorId 统一使用 uid：主播改名、换房间号都不会失效。
 */
object BilibiliPlatform : LivePlatform {

    override val id = "bilibili"
    override val displayName = "哔哩哔哩"
    override val usesOfficialApi = true
    override val degradeNote =
        "使用 B 站官方公开直播接口（免登录、支持批量查询），稳定性高，无需降级。"
    override val batchSize = 20
    override val minRequestGapMs = 800L

    private const val API_ROOM_INFO = "https://api.live.bilibili.com/room/v1/Room/get_info"
    private const val API_BATCH_STATUS =
        "https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids"

    private val limiter = RateLimiter(minRequestGapMs)

    override fun accept(raw: String): Boolean {
        val s = raw.trim()
        if (s.contains("bilibili.com", true) || s.contains("b23.tv", true)) return true
        // 纯数字默认按 B 站房间号处理；若确为抖音，可在添加页手动切换平台
        return s.matches(Regex("^\\d{1,12}$"))
    }

    override suspend fun resolve(raw: String): String? {
        var target = raw.trim()
        if (target.startsWith("http", true)) {
            if (target.contains("b23.tv", true)) {
                target = Http.finalUrl(target, limiter) ?: return null
            }
            Regex("""space\.bilibili\.com/(\d+)""").find(target)
                ?.groupValues?.get(1)?.let { return it }
            Regex("""live\.bilibili\.com/(?:blanc/)?(\d+)""").find(target)
                ?.groupValues?.get(1)?.let { return roomIdToUid(it) }
            return null
        }
        if (target.matches(Regex("^\\d{1,12}$"))) return roomIdToUid(target)
        return null
    }

    private suspend fun roomIdToUid(roomId: String): String? {
        return try {
            val body = Http.getJson("$API_ROOM_INFO?room_id=$roomId", limiter)
            val root = JSONObject(body)
            if (root.optInt("code", -1) != 0) return null
            val data = root.optJSONObject("data") ?: return null
            val uid = data.optLong("uid", 0L)
            if (uid == 0L) null else uid.toString()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        }
    }

    override suspend fun fetch(anchorIds: List<String>): List<Outcome> {
        val out = ArrayList<Outcome>(anchorIds.size)
        anchorIds.chunked(batchSize).forEach chunkLoop@{ chunk ->
            val url = buildString {
                append(API_BATCH_STATUS)
                chunk.forEachIndexed { i, uid ->
                    append(if (i == 0) "?" else "&")
                    append("uids[]=").append(uid)
                }
            }
            try {
                val root = JSONObject(Http.getJson(url, limiter))
                val code = root.optInt("code", -1)
                if (code != 0) {
                    val msg = root.optString("message").ifBlank { root.optString("msg", "接口异常") }
                    // -412 请求被拦截(限流)、-509 请求过于频繁 → 可重试；其余视为接口变更
                    val retryable = code == -412 || code == -509
                    chunk.forEach { out.add(Outcome.Failed(it, "B站接口 $code：$msg", retryable)) }
                    return@chunkLoop
                }
                val data = root.optJSONObject("data")
                if (data == null) {
                    chunk.forEach { out.add(Outcome.Failed(it, "B站接口缺少 data 字段", false)) }
                    return@chunkLoop
                }
                chunk.forEach uidLoop@{ uid ->
                    val item = data.optJSONObject(uid)
                    if (item == null) {
                        out.add(Outcome.Missing(uid, "主播不存在、已注销或未开通直播间"))
                        return@uidLoop
                    }
                    if (!item.has("live_status")) {
                        out.add(Outcome.Failed(uid, "B站接口缺少 live_status 字段", false))
                        return@uidLoop
                    }
                    val status = item.optInt("live_status", -1)
                    if (status !in 0..2) {
                        out.add(Outcome.Failed(uid, "B站返回未知的 live_status=$status", false))
                        return@uidLoop
                    }
                    val roomId = item.optLong("room_id", 0L)
                    val shortId = item.optLong("short_id", 0L)
                    val displayId = if (roomId > 0) roomId.toString() else uid
                    out.add(
                        Outcome.Ok(
                            uid,
                            Snapshot(
                                anchorId = uid,
                                nickname = item.optString("uname").ifBlank { "B站主播 $displayId" },
                                avatarUrl = item.optString("face", "").takeIf { it.isNotBlank() },
                                title = item.optString("title", ""),
                                // 1=直播中；2=轮播（按未开播处理，不推送）；0=未开播
                                state = if (status == 1) LiveState.LIVE else LiveState.OFF,
                                liveStartAt = item.optLong("live_time", 0L),
                                roomUrl = if (roomId > 0) "https://live.bilibili.com/$roomId"
                                else fallbackUrl(uid),
                                displayId = displayId,
                                note = when (status) {
                                    2 -> "轮播中"
                                    else -> if (shortId > 0) "短号 $shortId" else null
                                }
                            )
                        )
                    )
                }
            } catch (ce: CancellationException) {
                // 停止监控时不能把取消当成「网络失败」继续跑完这一批
                throw ce
            } catch (t: Throwable) {
                chunk.forEach { out.add(Outcome.Failed(it, t.message ?: "网络异常", true)) }
            }
        }
        return out
    }

    override fun fallbackUrl(anchorId: String): String = "https://space.bilibili.com/$anchorId"
}
