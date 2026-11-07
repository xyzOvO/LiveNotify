package com.example.livenotify.monitor

import com.example.livenotify.data.Settings

/** 影响轮询间隔的网络类型；OTHER = 判断不出来（按「非 Wi-Fi 的未知网络」保守处理） */
enum class NetworkKind { WIFI, CELLULAR, OTHER }

/**
 * 实际轮询间隔的唯一算法。
 *
 * 设置页要告诉用户「现在到底多久检测一次」，后台循环又要按同一个值 delay，
 * 两边必须共用这段逻辑——否则界面写「每 6 分钟」、服务却每 10 分钟跑一次，
 * 用户按界面对推算开播延迟就永远对不上。
 */
object PollSchedule {

    /** 下限 30 秒：再短就是打接口了；上限 30 分钟：再久就失去「开播提醒」的意义 */
    const val MIN_MS = 30_000L
    const val MAX_MS = 30 * 60_000L

    /** 免打扰时段内的降频倍数 */
    const val QUIET_FACTOR = 5

    data class Plan(
        val baseSec: Int,
        val mobileFactor: Int,
        val quiet: Boolean,
        val network: NetworkKind,
        /** 「仅 Wi-Fi」开着、当前却不是 Wi-Fi：本轮完全不会联网 */
        val paused: Boolean,
        /** 真正生效的间隔（毫秒，已收敛到 [MIN_MS]..[MAX_MS]） */
        val effectiveMs: Long,
        /** 理论值被上下限截断过，界面要说明「最多 30 分钟」 */
        val clamped: Boolean
    ) {
        val effectiveSec: Int get() = (effectiveMs / 1000L).toInt()

        /** 一天约跑多少轮：让「间隔越短、请求越频繁」变成可感知的数字 */
        val roundsPerDay: Int get() = (86_400L / effectiveSec.coerceAtLeast(1)).toInt()

        /** 是否有降频因素（移动网络倍数 / 免打扰） */
        val throttled: Boolean get() = effectiveSec > baseSec
    }

    fun plan(cfg: Settings, network: NetworkKind, quiet: Boolean): Plan {
        var ms = cfg.pollIntervalSec * 1000L
        if (network == NetworkKind.CELLULAR) ms *= cfg.mobileFactor.coerceAtLeast(1)
        if (quiet) ms *= QUIET_FACTOR
        val bounded = ms.coerceIn(MIN_MS, MAX_MS)
        return Plan(
            baseSec = cfg.pollIntervalSec,
            mobileFactor = cfg.mobileFactor,
            quiet = quiet,
            network = network,
            paused = cfg.wifiOnly && network != NetworkKind.WIFI,
            effectiveMs = bounded,
            clamped = bounded != ms
        )
    }

    /**
     * 下一轮的开始时刻：**固定周期**调度。
     *
     * 周期从「本轮开始」算，而不是从「本轮结束」算。按结束算的话，每轮检测本身的耗时
     * （抖音逐个数请求、网络慢时更明显）会一轮一轮累加进间隔里：设置 2 分钟、
     * 实际两轮相隔 2 分 40 秒，用户按界面上的数字推算开播延迟永远对不上。
     *
     * 单轮超过一个周期时**跳过**错过的时间点，不连续补跑：补跑只会让请求越堆越密，
     * 而开播提醒要的是「尽快知道现在」，不是「把过去每一次都补一遍」。
     *
     * @param tickStartMs 本轮开始时刻
     * @param periodMs 本轮生效的周期（已含移动网络倍数 / 免打扰降频）
     * @param nowMs 当前时刻（通常等于本轮结束时刻）
     */
    fun nextTickAt(tickStartMs: Long, periodMs: Long, nowMs: Long): Long {
        val period = periodMs.coerceIn(MIN_MS, MAX_MS)
        val first = tickStartMs + period
        // 正常情况：本轮还在一个周期之内，下一轮就是 tickStart + period
        if (nowMs < first) return first
        // 超时：直接跳到 now 之后的第一个格点，中间错过的整轮全部丢弃
        val missed = (nowMs - tickStartMs) / period + 1
        return tickStartMs + missed * period
    }

    /**
     * 同一主播的推送冷却是否已过。
     *
     * 用「现在 - 上次推送」直接比较时，用户把系统时间往回调会让差值变成负数，
     * 于是要等到真实时间追上那个「未来的上次推送」才恢复提醒——可能几小时。
     * 时间被调过就当冷却已过：同一场次还有 lastNotifiedLiveStart 兜着，不会重复推。
     */
    fun cooldownPassed(lastNotifiedAt: Long, now: Long, cooldownMin: Int): Boolean {
        if (cooldownMin <= 0) return true
        if (lastNotifiedAt <= 0L) return true
        val since = now - lastNotifiedAt
        if (since < 0L) return true
        return since >= cooldownMin * 60_000L
    }

    /**
     * 免打扰区间判定。
     * 起止相同视为「不生效」——和界面上的红色提示保持一致，避免用户以为设置成功了。
     */
    fun inQuietWindow(startMin: Int, endMin: Int, minuteOfDay: Int): Boolean = when {
        startMin == endMin -> false
        startMin < endMin -> minuteOfDay >= startMin && minuteOfDay < endMin
        // 跨零点，例如 23:00 → 08:00
        else -> minuteOfDay >= startMin || minuteOfDay < endMin
    }
}
