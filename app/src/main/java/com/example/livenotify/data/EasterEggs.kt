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
 *
 * 注：具体条目不随公开仓库分发，占位符 `@@ENTRIES@@` 处保持为空即可，
 * 需要时在本地按上面的格式补上。
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
        val text: String
    )

    private val ENTRIES: List<Entry> = listOf(
        // @@ENTRIES@@
    )

    private val BY_KEY: Map<String, Egg> = ENTRIES.associate {
        "${it.platformId}:${it.id.trim()}" to Egg(it.platformId, it.nickname, it.text)
    }

    /**
     * 按 平台 + 标识 查配置。anchorId（uid / web_rid）与 displayId（房间号）都会尝试，
     * 这样无论用户是用主键还是房间号添加的，都能命中同一条。
     */
    fun find(platformId: String, anchorId: String, displayId: String): Egg? =
        BY_KEY["$platformId:${anchorId.trim()}"]
            ?: BY_KEY["$platformId:${displayId.trim()}"]

    /** 供设置/关于页展示已配置数量 */
    fun count(): Int = ENTRIES.size
}
