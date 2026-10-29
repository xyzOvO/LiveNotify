package com.example.livenotify.platform

import com.example.livenotify.net.Http
import com.example.livenotify.net.RateLimiter

/**
 * 抖音：官方开放平台未提供个人可用的「直播间状态」查询接口
 * （小程序侧 ma.jsapi.live_room_status 属小程序组件能力；移动应用的直播相关接口需企业资质 + 审核）。
 * 因此这里采用**降级方案**：抓取 webcast 移动端落地页中的房间数据。
 *
 * 为什么是移动端落地页 `webcast.amemv.com/douyin/webcast/reflow/<房间号>`：
 *   - PC 端 live.douyin.com/<房间号> 自 2026-10 起不再在 HTML 里内联房间数据（只剩 App 壳）；
 *   - webcast 的 JSON 接口（/webcast/room/web/enter/）需要 a_bogus 风控签名，服务端实现不了；
 *   - 移动端落地页免登录、免签名，房间数据直接内联，且只有 90KB（PC 页 950KB），省流量省电。
 *
 * 关键：该页面只认移动端 UA，PC UA 会直接 404。
 *
 * 代价：
 * 1. 无法批量查询，只能逐个请求（流量与耗电高于 B 站），已做更保守的节流（1.5s/次）。
 * 2. 页面结构由抖音控制，改版后解析会失败；此处做了多路径解析 + 风控识别，
 *    失败时明确标记为「不可重试」并提示用户，绝不猜状态、不误报开播。
 * 3. 风控严重时可在设置页填入 Cookie（用户自行从浏览器复制）提升成功率。
 */
object DouyinPlatform : LivePlatform {

    override val id = "douyin"
    override val displayName = "抖音"
    override val usesOfficialApi = false
    override val degradeNote =
        "抖音官方未开放个人可用的直播间状态接口。当前为网页公开数据降级方案：读取 webcast 移动端落地页，逐个请求、节流 1.5 秒/次；页面改版可能导致解析失败（届时会明确提示，不会误报开播）。风控严重时在下方填写 Cookie 可提升成功率。"
    override val batchSize = 1
    override val minRequestGapMs = 1500L

    /** 由设置页写入，空表示不带 Cookie */
    @Volatile
    var cookie: String = ""

    private val limiter = RateLimiter(minRequestGapMs)

    /** web_rid 是雪花 ID，长度约 19；短于 15 的通常是抖音号（shortId），需要转换 */
    private const val WEB_RID_MIN_LEN = 15

    override fun accept(raw: String): Boolean {
        val s = raw.trim()
        if (s.contains("douyin.com", true) || s.contains("iesdouyin.com", true)) return true
        if (s.contains("amemv.com", true)) return true
        // web_rid 是 19 位纯数字，超过 B 站房间号位数（1~12），可以安全判给抖音
        if (s.matches(Regex("^\\d{13,20}$"))) return true
        // 抖音号 / web_rid 常见形态：字母数字混合（短纯数字优先判给 B 站）
        return s.matches(Regex("^[A-Za-z0-9_]{6,24}$")) && !s.matches(Regex("^\\d+$"))
    }

    override suspend fun resolve(raw: String): String? {
        var target = raw.trim()
        if (target.startsWith("http", true)) {
            // 短链（v.douyin.com/xxxx）与任何抖音域名都先跟随跳转到真实地址；
            // 分享文案里的 v.douyin.com 会跳到 webcast.amemv.com/.../reflow/<房间号>
            if (!isResolvedHost(target)) {
                target = Http.finalUrl(target, limiter) ?: target
            }
            extractRoomId(target)?.let { return normalize(it) }
            Regex("""douyin\.com/user/([A-Za-z0-9_\-]+)""").find(target)
                ?.groupValues?.get(1)?.let { return roomIdFromUserPage(it) }
            return null
        }
        // 抖音 web_rid 本身就是纯数字。自动识别时纯数字归 B 站（见 accept），
        // 但用户手动指定抖音平台时，纯数字就是 web_rid 或抖音号，必须放行。
        if (target.matches(Regex("^\\d{1,20}$"))) return normalize(target)
        return if (accept(target)) target else null
    }

    /** 已经是能直接取数据的地址（含房间号）就无需再跳转 */
    private fun isResolvedHost(url: String): Boolean =
        url.contains("webcast.amemv.com", true) || url.contains("live.douyin.com", true)

    /** 从各种形态的地址里抠出房间号 */
    private fun extractRoomId(url: String): String? {
        Regex("""reflow/(\d{6,25})""").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("""live\.douyin\.com/(\d{6,25})""").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("""[?&]room_id=(\d{6,25})""").find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    /**
     * 抖音号（shortId，如 629458933）不是 web_rid，落地页查不到数据。
     * live.douyin.com/<抖音号> 用移动端 UA 会 302 到真正的 reflow 地址，据此取回 web_rid。
     */
    private suspend fun toWebRid(id: String): String? {
        if (id.length >= WEB_RID_MIN_LEN) return null
        val final = Http.finalUrl("https://live.douyin.com/$id", limiter, Http.UA_MOBILE)
            ?: return null
        val rid = extractRoomId(final) ?: return null
        return if (rid.length >= WEB_RID_MIN_LEN) rid else null
    }

    /** 尽量把标识统一成 web_rid；转换不出来就保持原样（例如主播当前没开播） */
    private suspend fun normalize(id: String): String = toWebRid(id) ?: id

    /** 用户主页链接 -> 直播间 web_rid。抖音对未登录请求风控较重，失败时返回 null 并提示用户改用直播间链接 */
    private suspend fun roomIdFromUserPage(secUid: String): String? {
        return try {
            val html = Http.get(
                "https://www.douyin.com/user/$secUid",
                mapOf("Referer" to "https://www.douyin.com/"),
                cookie.takeIf { it.isNotBlank() },
                limiter,
                Http.UA_MOBILE
            )
            DouyinParser.findString(html, "web_rid")
                ?: DouyinParser.findString(html, "room_id")
                ?: DouyinParser.findString(html, "roomId")
        } catch (t: Throwable) {
            null
        }
    }

    override suspend fun fetch(anchorIds: List<String>): List<Outcome> {
        val out = ArrayList<Outcome>(anchorIds.size)
        val ck = cookie.takeIf { it.isNotBlank() }
        for (rid in anchorIds) out.add(fetchOne(rid, ck))
        return out
    }

    private suspend fun fetchOne(rid: String, ck: String?): Outcome {
        var parsed = loadReflow(rid, ck)

        // 库里存的是抖音号（早期版本写入的旧数据）：先换成 web_rid 再取一次
        if (parsed == null && rid.length < WEB_RID_MIN_LEN) {
            val real = toWebRid(rid)
            if (real != null && real != rid) parsed = loadReflow(real, ck)
        }

        if (parsed == null) {
            val msg = DouyinParser.lastReason
            return if (msg.contains("风控")) Outcome.Failed(rid, msg, true)
            else Outcome.Failed(rid, msg, false)
        }
        return when {
            parsed.notFound -> Outcome.Missing(rid, "直播间不存在或已注销")
            parsed.status != STATUS_LIVE && parsed.status != STATUS_OFF ->
                Outcome.Failed(rid, "抖音返回未知的直播间状态 ${parsed.status}", false)
            else -> Outcome.Ok(
                rid,
                Snapshot(
                    anchorId = rid,
                    nickname = parsed.nickname.ifBlank { "抖音主播 $rid" },
                    avatarUrl = parsed.avatar,
                    title = parsed.title,
                    state = if (parsed.status == STATUS_LIVE) LiveState.LIVE else LiveState.OFF,
                    liveStartAt = parsed.createTime,
                    roomUrl = "https://live.douyin.com/${parsed.webRid}",
                    displayId = parsed.webRid.ifBlank { rid }
                )
            )
        }
    }

    private suspend fun loadReflow(rid: String, ck: String?): DouyinParser.Parsed? {
        val html = try {
            Http.get(
                "https://webcast.amemv.com/douyin/webcast/reflow/$rid",
                emptyMap(),
                ck,
                limiter,
                Http.UA_MOBILE
            )
        } catch (t: Throwable) {
            val msg = t.message ?: ""
            if (msg.contains("404")) return DouyinParser.Parsed(rid, "", "", STATUS_OFF, null, 0L, notFound = true)
            return null
        }
        return DouyinParser.parseReflow(html, rid)
    }

    override fun fallbackUrl(anchorId: String): String = "https://live.douyin.com/$anchorId"

    const val STATUS_LIVE = 2
    const val STATUS_OFF = 4
}
