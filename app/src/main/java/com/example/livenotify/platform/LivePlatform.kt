package com.example.livenotify.platform

/**
 * 平台抽象层。新增平台只需实现 [LivePlatform] 并在 [PlatformRegistry] 注册，
 * UI / 检测引擎 / 数据库均无需改动。
 */

enum class LiveState { OFF, LIVE, UNKNOWN }

data class Snapshot(
    val anchorId: String,          // 平台内稳定标识（B站=uid，抖音=web_rid）
    val nickname: String,
    val avatarUrl: String?,
    val title: String,
    val state: LiveState,
    val liveStartAt: Long,         // 开播时间戳（秒），用于同一场次去重；未知为 0
    val roomUrl: String,
    val displayId: String = anchorId, // 展示给用户的房间号
    val note: String? = null       // 例如「轮播中」这类附加说明
)

sealed interface Outcome {
    val anchorId: String
    /** 成功拿到状态 */
    data class Ok(override val anchorId: String, val snapshot: Snapshot) : Outcome
    /** 主播/房间不存在、已注销、未开通直播间——应当提示用户，不应反复重试 */
    data class Missing(override val anchorId: String, val reason: String) : Outcome
    /** 网络异常（可重试）或解析失败/接口变更（不可重试） */
    data class Failed(override val anchorId: String, val reason: String, val retryable: Boolean) : Outcome
}

interface LivePlatform {
    /** 内部标识，存库用，不可变更 */
    val id: String
    val displayName: String
    /** 是否走官方开放接口 */
    val usesOfficialApi: Boolean
    /** 降级说明，展示在设置页 */
    val degradeNote: String
    /** 单次请求可查询的主播数，1 表示无法批量 */
    val batchSize: Int
    /** 最小请求间隔（毫秒），用于限流 */
    val minRequestGapMs: Long

    fun accept(raw: String): Boolean

    /** 把用户粘贴的链接/房间号解析为 anchorId；无法识别返回 null */
    suspend fun resolve(raw: String): String?

    suspend fun fetch(anchorIds: List<String>): List<Outcome>

    /** 兜底跳转链接（正常情况用 Snapshot.roomUrl，它总是最新） */
    fun fallbackUrl(anchorId: String): String
}

object PlatformRegistry {
    private val platforms = listOf<LivePlatform>(BilibiliPlatform, DouyinPlatform, DouyuPlatform, HuyaPlatform)

    fun all(): List<LivePlatform> = platforms

    fun get(id: String): LivePlatform? = platforms.firstOrNull { it.id == id }

    /** 依 raw 内容猜测平台 */
    fun detect(raw: String): LivePlatform? = platforms.firstOrNull { it.accept(raw) }
}
