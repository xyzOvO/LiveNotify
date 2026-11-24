package com.example.livenotify.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ShapeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme

/**
 * 全应用统一的设计令牌（Design Tokens）。
 *
 * 目的：把散落各页面的圆角 / 间距 / 字号收敛到一处，避免「9 种圆角、十几档间距」的漂移。
 * 页面只引用这里的具名常量，不再自己写 RoundedCornerShape(14.dp) 之类的裸值。
 *
 * 颜色不在这里定义——ARGB 已全部集中在 Theme.kt；本文件只提供**语义色**的取色函数，
 * 让「成功/直播/警告」这类状态色只有一个出口，深浅主题自动适配。
 */
object Dimens {
    // 圆角
    val RadiusSmall = 10.dp      // 小组件：徽标、输入框
    val RadiusMedium = 16.dp     // 卡片
    val RadiusLarge = 20.dp      // 大容器 / BottomSheet
    val RadiusPill = 50.dp       // 胶囊

    // 间距
    val SpaceXS = 4.dp
    val SpaceS = 8.dp
    val SpaceM = 12.dp
    val SpaceL = 16.dp
    val SpaceXL = 24.dp

    // 字号
    val TextCaption = 12.sp
    val TextBody = 14.sp
    val TextSubtitle = 16.sp
    val TextTitle = 22.sp

    // 触控
    val MinTouch = 48.dp

    val ShapeSmall = RoundedCornerShape(RadiusSmall)
    val ShapeMedium = RoundedCornerShape(RadiusMedium)
    val ShapeLarge = RoundedCornerShape(RadiusLarge)
    val ShapePill = RoundedCornerShape(RadiusPill)
    val ShapeDefault = ShapeDefaults.Medium
}

/**
 * 语义色：状态色只有一个出口，深浅主题自动取色。
 * - 正在监控 / 成功 → 绿
 * - 正在直播 → 珊瑚红（中国习惯：红=涨/热）
 * - 异常 / 警告 → 琥珀
 * - 已暂停 / 未开播 → 中性灰（弱化）
 */
object StatusColors {
    @Composable
    fun success(): Color = okColor()

    @Composable
    fun successContainer(): Color = okContainer()

    @Composable
    // 珊瑚红/琥珀原色在浅色卡片上只有 3.7 / 2.6:1（低于 4.5），
    // 文字态状态色按主题加深；LiveAccent/Warning 原色留给图表填充不变。
    fun live(): Color = if (EffectiveDark.isDark) LiveAccent else Color(LIVE_TEXT_LIGHT)

    @Composable
    fun warning(): Color = if (EffectiveDark.isDark) Warning else Color(WARNING_TEXT_LIGHT)

    @Composable
    fun neutral(): Color = MaterialTheme.colorScheme.outline

    /** 需高对比的文字：在中性灰上用 outline，不用弱化 alpha */
    @Composable
    fun muted(): Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)
}

/** 警告琥珀色，深浅各一版 */
val Warning = Color(0xFFE08A1E)

/** 浅色主题下的状态文字色：原色对白底对比不足，这里加深到 ≥4.5:1 */
internal val LIVE_TEXT_LIGHT = 0xFFC42B34L
internal val WARNING_TEXT_LIGHT = 0xFFA85C05L
