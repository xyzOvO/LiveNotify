package com.example.livenotify.platform

/**
 * 抖音直播间解析器（降级方案的核心）。
 *
 * 设计原则：**宁可报「解析失败」，也不猜状态**。
 * 只有在明确读到房间状态字段时才给出开播/未开播结论，否则返回 null 由上层标记为不可重试错误。
 *
 * 数据源：移动端落地页 `https://webcast.amemv.com/douyin/webcast/reflow/<房间号>`。
 * 选它的理由：
 *   1. 免登录、无需风控签名（X-Bogus / a_bogus 那套由页面内 JS 生成，这里用不到）；
 *   2. 房间数据内联在页面的 RSC flight 片段里，一次 GET 就能拿到状态、标题、主播名、头像；
 *   3. 体积约 90KB，远小于 PC 端 live.douyin.com 的 950KB 页面，省流量省电。
 *
 * 两个已踩过的坑（所以不再用它们）：
 *   - PC 端 live.douyin.com/<房间号>：2026-10 起 SSR 不再内联房间数据，只剩 950KB 的 App 壳，
 *     解析不出任何状态；
 *   - /webcast/room/web/enter/ 接口：需要风控签名，无签名一律返回「该内容暂时无法查看」。
 */
object DouyinParser {

    @Volatile
    var lastReason: String = "抖音返回空内容"
        private set

    data class Parsed(
        val webRid: String,
        val nickname: String,
        val title: String,
        val status: Int,   // 2=直播中，4=未开播/已结束
        val avatar: String?,
        val createTime: Long,
        val notFound: Boolean = false
    )

    /**
     * 解析移动端落地页。页面里的房间数据（已还原转义）长这样：
     *
     *   "room":{"id":7701909481856634000,"idStr":"7701909481856633636","status":2,
     *           "ownerUserId":61608272917,"title":"…","userCount":58589,"createTime":1793240555,…}
     *   "owner":{"id":61608272917,"shortId":629458933,"nickname":"女流66",…,
     *            "avatarThumb":{"urlList":["https://…"]}}
     *
     * 关键点：**必须锚定 `"room":{"id":`**，再在它之后的小窗口内取 status。
     * 页面里还有 `"user":{"id":0,…}`（访客）和多处无关 status 字段，
     * 盲目取第一个 status 会把无关值当成房间状态。
     */
    fun parseReflow(html: String, fallbackRid: String): Parsed? {
        if (html.isBlank()) {
            lastReason = "抖音返回空内容"
            return null
        }
        val text = unescape(html)

        val anchor = listOf(
            text.indexOf("\"room\":{\"id\":"),
            text.indexOf("\"room\":{\"idStr\":")
        ).filter { it >= 0 }.minOrNull()
        if (anchor == null) return diagnose(html, fallbackRid)

        // status / title / createTime 都紧跟在房间对象开头，取一小段窗口即可
        val window = text.substring(anchor, (anchor + 6000).coerceAtMost(text.length))
        val status = findLong(window, "status")?.toInt()
            ?: return diagnose(html, fallbackRid)

        return Parsed(
            webRid = findString(window, "idStr") ?: fallbackRid,
            nickname = findOwnerNickname(text),
            title = findString(window, "title") ?: "",
            status = status,
            avatar = findAvatar(text),
            createTime = findLong(window, "createTime") ?: 0L
        )
    }

    /**
     * 主播昵称。页面里有多处 `nickname`（访客对象也有，值为 `$undefined`），
     * 逐个候选窗口跳过空白与占位值，取第一个真实昵称。
     */
    private fun findOwnerNickname(text: String): String {
        var from = 0
        while (true) {
            val idx = text.indexOf("\"owner\":{", from)
            if (idx < 0) return ""
            from = idx + 1
            val w = text.substring(idx, (idx + 4000).coerceAtMost(text.length))
            val nick = findString(w, "nickname")
            if (!nick.isNullOrBlank() && nick != "\$undefined") return nick
        }
    }

    private fun diagnose(html: String, fallbackRid: String): Parsed? {
        // 落地页被风控时会退化成一个很短的壳页（约 26KB）且不含房间数据
        if (html.length < 120_000 && (
                html.contains("verify_center", true) || html.contains("captcha", true) ||
                    html.contains("滑块") || html.contains("验证码")
                )
        ) {
            lastReason = "触发抖音风控，建议在设置页填写 Cookie"
            return null
        }
        if (html.length < 4000 &&
            (html.contains("不存在") || html.contains("404") || html.contains("notfound", true))
        ) {
            lastReason = "直播间不存在"
            return Parsed(fallbackRid, "", "", 4, null, 0L, notFound = true)
        }
        // 没有 room 锚点就不能证明主播已下播。短空壳可能只是风控、临时故障或页面改版，
        // 这里必须返回解析失败，让上层保留已知状态；只有房间自身的 status=4 才算未开播。
        if (html.length < 40_000) {
            lastReason = "抖音未返回房间状态，已保留上次检测结果"
            return null
        }
        lastReason = "抖音页面结构可能已变更（未找到房间数据）"
        return null
    }

    internal fun findString(src: String, key: String): String? {
        val r = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return r.find(src)?.groupValues?.get(1)?.let { unescapeValue(it) }
    }

    private fun findLong(src: String, key: String): Long? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(src)?.groupValues?.get(1)?.toLongOrNull()

    private fun findAvatar(src: String): String? {
        Regex("""\"avatarThumb\"\s*:\s*\{\s*\"urlList\"\s*:\s*\[\s*\"([^\"]+)\"""")
            .find(src)?.groupValues?.get(1)?.let { return unescapeValue(it) }
        return Regex("""\"avatarLarger\"\s*:\s*\{\s*\"urlList\"\s*:\s*\[\s*\"([^\"]+)\"""")
            .find(src)?.groupValues?.get(1)?.let { unescapeValue(it) }
    }

    private fun unescape(s: String): String =
        s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\u0026", "&")

    private fun unescapeValue(v: String): String =
        v.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\u0026", "&")
}
