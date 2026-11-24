package com.example.livenotify.ui

/**
 * 分组的自动配色与图标（第一版：不改数据库，按分组名稳定派生）。
 *
 * 颜色从一组预设里按名字哈希取，保证：同一个组每次打开颜色一致、不同组尽量分散。
 * 不引入 icon/颜色字段，是因为第一版单归属分组用不到那么多自定义——用户要的是
 * 「一眼分得清」，不是给每个组建一套视觉资产。等真有需求再加字段+迁移。
 */

/** 预设分组色（ARGB，深浅通用，饱和度适中） */
private val GROUP_COLORS = longArrayOf(
    0xFF4C63D2L, // 靛蓝
    0xFF2E9E7BL, // 青绿
    0xFFD9802AL, // 琥珀
    0xFFB84E9AL, // 紫
    0xFF3E8FC7L, // 蓝
    0xFFC75A5AL  // 砖红
)

/** 分组名 → 稳定的颜色索引（正哈希，避免 MIN_VALUE 溢出取绝对值） */
internal fun groupColorIndex(name: String): Int {
    if (name.isEmpty()) return 0
    val h = name.hashCode() and 0x7FFFFFFF
    return h % GROUP_COLORS.size
}

internal fun groupColorArgb(name: String): Long = GROUP_COLORS[groupColorIndex(name)]

/** 未分组用中性色，不与自定义组抢眼 */
internal val UNGROUPED_COLOR = 0xFF8B90A0L

/**
 * 固定 ARGB 分组色作首字时，浅色下太淡（琥珀 2.7:1）、深色下太暗（靛蓝 3.2:1），
 * 都低于 WCAG 4.5:1。按当前主题双向校正：浅色加深到 58%、深色朝白色提亮 42%，
 * 两版对各自卡片底都 ≥5:1。不改数据库里的颜色值，只改显示。
 */
internal fun readableGroupColor(argb: Long, isDark: Boolean): Long {
    // Long 的 shr/and 必须配 Long 掩码，取出来再转 Int
    val r = ((argb shr 16) and 0xFFL).toInt()
    val g = ((argb shr 8) and 0xFFL).toInt()
    val b = (argb and 0xFFL).toInt()
    fun adjust(c: Int) = if (isDark) c + ((255 - c) * 0.42).toInt() else (c * 0.58).toInt()
    // Int 里拼好再转 Long，避免 Long shl 的位移量类型歧义
    val packed = (0xFF shl 24) or (adjust(r) shl 16) or (adjust(g) shl 8) or adjust(b)
    return packed.toLong() and 0xFFFFFFFFL
}
