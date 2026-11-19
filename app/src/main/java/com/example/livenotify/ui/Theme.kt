package com.example.livenotify.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 主题模式：跟随系统 / 强制浅色 / 强制深色 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 主题偏好：独立的 SharedPreferences，不进 Settings/备份——主题是设备外观偏好，
 * 不该跟着关注列表备份迁移到另一台设备。
 *
 * [mode] 是单一数据源；App 启动时 [init] 读盘，设置页改模式走 [set]，
 * 所有 @Composable 通过 collectAsState 订阅，切换即时生效。
 */
object ThemePrefs {
    private const val PREFS = "theme"
    private const val KEY_MODE = "mode"
    private var prefs: SharedPreferences? = null

    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _mode.value = runCatching {
            ThemeMode.valueOf(prefs!!.getString(KEY_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun set(value: ThemeMode) {
        _mode.value = value
        prefs?.edit()?.putString(KEY_MODE, value.name)?.apply()
    }
}

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
    val mode by ThemePrefs.mode.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // 让语义色函数（okColor 等）读到实际生效的明暗，而不是系统明暗——
    // 三态模式下「强制浅色」时系统可能是深色，必须一致。
    EffectiveDark.isDark = dark
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}

/** 供非 @Composable 的语义色取当前生效明暗（由 LiveNotifyTheme 每次组合时写入） */
internal object EffectiveDark {
    var isDark: Boolean = false
        internal set
}

/** 开播高亮色（中系习惯：开播=红） */
val LiveAccent = Color(0xFFE5484D)

/** 状态绿（API 官方接口标识 / 监控正常）：深浅模式各给一版，保证对比度 */
@Composable
fun okColor(): Color = if (EffectiveDark.isDark) Color(0xFF7BD88A) else Color(0xFF2E7D32)

/** 状态绿的容器底色 */
@Composable
fun okContainer(): Color =
    if (EffectiveDark.isDark) Color(0xFF22432B) else Color(0xFF2E7D32).copy(alpha = 0.12f)

/**
 * 卡片 / 条状容器的底色。
 * 深色模式下 surfaceVariant 与 background 的差值本来就很小，再叠透明度会让卡片边界几乎看不见，
 * 所以只在浅色模式降透明度（浅色 surfaceVariant 偏亮，压一点才不与背景打架）。
 */
@Composable
fun cardSurface(alphaInLight: Float = 0.5f): Color {
    val v = MaterialTheme.colorScheme.surfaceVariant
    return if (EffectiveDark.isDark) v else v.copy(alpha = alphaInLight)
}

/** 各平台品牌色，用于头像徽标与标签 */
fun platformAccent(platformId: String): Color = when (platformId) {
    "bilibili" -> Color(0xFFFB7299)
    "douyin" -> Color(0xFF1F1F2E)
    "douyu" -> Color(0xFFFF7700)
    else -> Color(0xFF7C8499)
}
