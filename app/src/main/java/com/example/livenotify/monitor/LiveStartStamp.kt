package com.example.livenotify.monitor

/**
 * 「上次开播」时间戳的记账规则：只前进，不后退。
 *
 * 平台给的开播时刻并不总是「最新一场」的：
 * - 斗鱼未开播时仍返回上一场 start_time；
 * - 抖音下播后 / 刚开播的一两个轮询里，createTime 会退回更早的一场。
 *
 * 若无条件采信平台值，列表里的「上次开播」就会从「刚刚」倒退回十几小时前——
 * 而历史记录里明明刚写过一条开播提醒，用户看到的就是自相矛盾的界面。
 *
 * 因此只接受比已有记录更新的观测值：时间戳一旦前进就不再被旧值覆盖。
 * 该字段只用于展示（列表副标题、统计），不参与推送去重，所以单调化不影响提醒逻辑。
 */
internal fun keepLatestLiveStart(previous: Long, observed: Long): Long =
    maxOf(previous, observed)
