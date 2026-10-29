package com.example.livenotify.data

/**
 * 关注问候：添加特定主播后，弹一句该主播的专属台词。
 *
 * ── 如何新增一条 ────────────────────────────────────────────────
 * 在 [ENTRIES] 里加一行 `Entry(平台id, 标识, 展示名, 台词)`：
 *
 *   - 平台 id：`bilibili` / `douyin` / `douyu` / `huya`（见各 Platform 的 `id`
 *     字段，也是库里 `platformId` 的取值）
 *   - 标识：**填 uid 或房间号都可以**。B 站库里 `anchorId` 存的是 uid、
 *     `displayId` 存的是房间号，匹配时两个都会试；抖音存 web_rid、斗鱼/虎牙存
 *     房间号，填对应那个即可。
 *   - 展示名：仅作代码可读性备注，弹窗落款用的是接口返回的真实昵称。
 *
 * 匹配不上就静默不弹，不会影响正常添加流程。
 * ────────────────────────────────────────────────────────────────
 */
object EasterEggs {

    /** 命中配置时抛给 UI 的数据 */
    data class Egg(
        val platformId: String,
        val nickname: String,
        val text: String
    )

    private data class Entry(
        val platformId: String,
        val id: String,
        val nickname: String,
        val text: String,
        /**
         * 是否同时按展示号码（房间号）匹配。
         * B 站 uid 偶尔会和别人的直播间房号数字撞车（例如 uid 7706705 与某个 7706705 房间），
         * 撞车时置 false，只认 uid，避免给不相干的主播弹词。
         */
        val matchDisplayId: Boolean = true
    )

    private val ENTRIES: List<Entry> = listOf(
        // @@ENTRIES@@
    )

    private val BY_KEY: Map<String, Egg> = ENTRIES.associate {
        "${it.platformId}:${it.id.trim()}" to Egg(it.platformId, it.nickname, it.text)
    }

    /** 展示号码索引：只收 matchDisplayId = true 的条目 */
    private val BY_DISPLAY: Map<String, Egg> = ENTRIES.filter { it.matchDisplayId }.associate {
        "${it.platformId}:${it.id.trim()}" to Egg(it.platformId, it.nickname, it.text)
    }

    /**
     * 按 平台 + 标识 查配置。先按 anchorId（uid / web_rid）匹配，再退到 displayId（房间号），
     * 这样无论用户是用主键还是房间号添加的，都能命中同一条。
     */
    fun find(platformId: String, anchorId: String, displayId: String): Egg? =
        BY_KEY["$platformId:${anchorId.trim()}"]
            ?: BY_DISPLAY["$platformId:${displayId.trim()}"]

    /** 供设置/关于页展示已配置数量 */
    fun count(): Int = ENTRIES.size
}
