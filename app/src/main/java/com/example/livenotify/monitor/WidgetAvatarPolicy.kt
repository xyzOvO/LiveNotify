package com.example.livenotify.monitor

/**
 * 小组件头像下载与缓存判据：无 Android 依赖，便于 JVM 单测回归锁边界。
 *
 * 抽出来的原因：缓存容量与 sizeOf 必须同用字节口径，历史上写成 KB 导致
 * 2.25MB 的预算缩水成 64KB（只放得下 1.7 张头像），头像反复重下。
 */
internal object WidgetAvatarPolicy {
    /** 缓存总预算：约 64 张 96×96 ARGB（2.25MB），按【字节】计。 */
    const val CACHE_MAX_BYTES: Int = 2_359_296 // 2.25 * 1024 * 1024

    /** 单张头像下载字节上限，异常大响应不一次性读入内存。 */
    const val MAX_DOWNLOAD_BYTES: Long = 2L * 1024 * 1024

    /** 解码后合理尺寸上限（宽或高），超过视为异常图片。 */
    const val MAX_DECODE_DIMENSION = 512

    /** 单张 dim×dim ARGB_8888 位图的字节数。 */
    fun avatarBytes(dim: Int = 96): Int = dim * dim * 4

    /** 缓存预算能容纳的典型头像张数（回归锁：单位错配时此值会掉到个位数）。 */
    fun cacheCapacityInAvatars(): Int = CACHE_MAX_BYTES / avatarBytes()

    /**
     * 是否接受响应头。仅 2xx；contentLength = -1 表示未知长度（分块传输），
     * 交给读取阶段的实际字节上限兜底。
     */
    fun acceptResponse(code: Int, contentLength: Long): Boolean =
        code in 200..299 && (contentLength < 0 || contentLength <= MAX_DOWNLOAD_BYTES)

    /** 读取过程中累计字节是否仍在上限内。 */
    fun withinReadLimit(total: Long): Boolean = total <= MAX_DOWNLOAD_BYTES

    /** 解码尺寸是否合理：有实际像素且不过大。 */
    fun reasonableSize(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width <= MAX_DECODE_DIMENSION && height <= MAX_DECODE_DIMENSION
}
