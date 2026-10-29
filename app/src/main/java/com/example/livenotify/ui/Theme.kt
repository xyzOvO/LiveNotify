package com.example.livenotify.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 品牌靛蓝，与头像插画的冷色调呼应 */
private val Brand = Color(0xFF4C63D2)
private val BrandDark = Color(0xFF3949A8)
private val Accent = Color(0xFFFF7A59)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E8FF),
    onPrimaryContainer = Color(0xFF101A4D),
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE4DC),
    onSecondaryContainer = Color(0xFF5C1900),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFF6F7FB),
    surfaceVariant = Color(0xFFEEF0F7),
    outline = Color(0xFF8B90A0),
    outlineVariant = Color(0xFFDDE0EA),
    error = Color(0xFFE5484D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9B6FF),
    onPrimary = Color(0xFF0B1547),
    primaryContainer = Color(0xFF23306E),
    onPrimaryContainer = Color(0xFFDDE2FF),
    secondary = Color(0xFFFFB59E),
    onSecondary = Color(0xFF5C1900),
    secondaryContainer = Color(0xFF7A2E14),
    onSecondaryContainer = Color(0xFFFFDBCF),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF1C1F26),
    outline = Color(0xFF8B90A0),
    outlineVariant = Color(0xFF2A2E38),
    error = Color(0xFFFF8A8D)
)

@Composable
fun LiveNotifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}

/** 开播高亮色（中系习惯：开播=红） */
val LiveAccent = Color(0xFFE5484D)

/** 状态绿（API 官方接口标识 / 监控正常）：深浅模式各给一版，保证对比度 */
@Composable
fun okColor(): Color = if (isSystemInDarkTheme()) Color(0xFF7BD88A) else Color(0xFF2E7D32)

/** 状态绿的容器底色 */
@Composable
fun okContainer(): Color =
    if (isSystemInDarkTheme()) Color(0xFF22432B) else Color(0xFF2E7D32).copy(alpha = 0.12f)

/** 各平台品牌色，用于头像徽标与标签 */
fun platformAccent(platformId: String): Color = when (platformId) {
    "bilibili" -> Color(0xFFFB7299)
    "douyin" -> Color(0xFF1F1F2E)
    "douyu" -> Color(0xFFFF7700)
    else -> Color(0xFF7C8499)
}
