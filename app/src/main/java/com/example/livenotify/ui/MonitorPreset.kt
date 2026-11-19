package com.example.livenotify.ui

import com.example.livenotify.data.Settings

/**
 * 检测三档预设。
 *
 * 设置页默认只让用户选「省电 / 默认 / 激进」——大多数人不需要知道
 * 轮询间隔、移动降频、确认次数、提醒间隔这四个参数各自是什么。
 * 想细调的用户走「详细设置」页，那里能单独改每一项。
 *
 * 只改检测节奏相关的四项，绝不动「仅 Wi-Fi 下检测」：
 * 那是流量策略（开了就完全不联网），跟快慢不是一回事，被预设偷偷改掉会很困惑。
 */
enum class MonitorPreset(
    val label: String,
    /** 一句话说明，帮用户判断该选哪个 */
    val hint: String,
    val intervalSec: Int,
    val mobileFactor: Int,
    val confirmTimes: Int,
    val cooldownMin: Int
) {
    POWER_SAVING(
        label = "省电",
        hint = "10 分钟查一次，移动网络再降频；不急着知道开播时选它",
        intervalSec = 600,
        mobileFactor = 5,
        confirmTimes = 1,
        cooldownMin = 15
    ),
    BALANCED(
        label = "默认",
        hint = "2 分钟查一次，及时性和耗电比较均衡",
        intervalSec = 120,
        mobileFactor = 3,
        confirmTimes = 1,
        cooldownMin = 10
    ),
    AGGRESSIVE(
        label = "激进",
        hint = "1 分钟查一次，移动网络也不降频；想第一时间收到提醒时选它",
        intervalSec = 60,
        mobileFactor = 1,
        confirmTimes = 1,
        cooldownMin = 5
    );

    companion object {
        /** 默认档，用于「恢复默认」这类兜底 */
        val DEFAULT = BALANCED
    }
}

/**
 * 当前设置对应哪一档；四项里有任何一项被单独改过就返回 null（界面显示「自定义」）。
 *
 * 判据必须是「全部相等」而不是「间隔相近」：用户把确认次数改成 2 次，
 * 节奏已经和预设不一样了，继续高亮某个档位会让人以为改的是别处。
 */
internal fun presetOf(settings: Settings): MonitorPreset? =
    MonitorPreset.entries.firstOrNull { p ->
        p.intervalSec == settings.pollIntervalSec &&
            p.mobileFactor == settings.mobileFactor &&
            p.confirmTimes == settings.confirmTimes &&
            p.cooldownMin == settings.cooldownMin
    }

/** 档位状态的展示文案：命中档位说档位，否则说自定义 */
internal fun presetLabel(settings: Settings): String =
    presetOf(settings)?.label ?: "自定义"
