package com.example.livenotify.platform

/**
 * 平台返回值的**纯解读逻辑**：不碰 org.json / 网络 / Android，
 * 便于 JVM 单测把「异常 JSON / 字段缺失 / 未知状态」这些分支锁死。
 *
 * 各平台 fetch() 只负责取数与构造 Snapshot，状态判定一律经过这里，
 * 避免测试要 mock org.json（本机 JVM 单测classpath 里没有实现）。
 */
internal object StatusInterpret {

    /** B站 live_status：1=直播中，2=轮播（按未开播处理），0=未开播；其余都是接口异常。 */
    fun biliStatus(status: Int): LiveState? = when (status) {
        1 -> LiveState.LIVE
        0, 2 -> LiveState.OFF
        else -> null
    }

    /** 斗鱼 room_status 是字符串："1"=直播中，"2"=未开播；包括空串在内的其它值都不可信。 */
    fun douyuStatus(raw: String): LiveState? = when (raw.trim()) {
        "1" -> LiveState.LIVE
        "2" -> LiveState.OFF
        else -> null
    }

    /**
     * 虎牙 liveStatus / realLiveStatus 交叉校验结果。
     * [valid]=false 表示主状态字段不可信，调用方应回 Failed(不可重试)；
     * [isLive] 只有在两个字段一致（或 real 缺失）时才为 true——宁可当作未开播，也不误报开播。
     */
    data class HuyaVerdict(val valid: Boolean, val isLive: Boolean)

    fun huyaStatus(liveRaw: String, realRaw: String): HuyaVerdict {
        val live = liveRaw.trim().uppercase()
        val real = realRaw.trim().uppercase()
        if (live != "ON" && live != "OFF") return HuyaVerdict(valid = false, isLive = false)
        val isLive = live == "ON" && (real.isBlank() || real == "ON")
        return HuyaVerdict(valid = true, isLive = isLive)
    }
}
