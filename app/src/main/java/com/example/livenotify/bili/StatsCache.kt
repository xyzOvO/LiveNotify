package com.example.livenotify.bili

import com.example.livenotify.util.JsonWriter
import com.example.livenotify.util.MiniJson
import java.io.File

/**
 * B 站数据页的本地缓存。
 *
 * 三个理由：
 *  1. 数据源是第三方社区接口，有 IP 频率限制。来回切主播、切 Tab 都重新请求，
 *     既慢又容易把自己撞进限流。
 *  2. 任意 UID 分析是服务端扫历史场次的异步任务，一次要几秒到几十秒，
 *     完全没必要每次进页面重跑。
 *  3. 断网时页面不该是一片空白——上次成功的数据比「加载失败」有用。
 *
 * 只缓存**整理后的分析结果**，不存原始互动记录：原始记录量大、变化快，
 * 存下来也没什么复用价值，还容易让用户以为看到的是实时的。
 */
object StatsCache {

    /** 超过这个时间就标成「旧数据」，但**不删**——旧数据仍然比空白强 */
    const val STALE_AFTER_MS = 6 * 3_600_000L

    private const val VERSION = 1

    data class ChannelEntry(
        val savedAt: Long,
        val channel: ChannelStats,
        val trend: List<TrendMonth>,
        val career: CareerStats?
    )

    data class AnalysisEntry(
        val userId: Long,
        val rangeDays: Int,
        val savedAt: Long,
        val fromMs: Long,
        val toMs: Long,
        val data: UserAnalysis
    )

    fun isStale(savedAt: Long, now: Long): Boolean =
        savedAt <= 0L || now - savedAt > STALE_AFTER_MS

    // ── 主播数据 ────────────────────────────────────────────────────────

    fun encodeChannel(e: ChannelEntry): String {
        val ch = e.channel
        return JsonWriter().obj {
            put("version", VERSION)
            put("savedAt", e.savedAt)
            obj("channel") {
                put("uid", ch.uid)
                put("name", ch.name)
                put("roomId", ch.roomId)
                put("faceUrl", ch.faceUrl)
                put("isLiving", ch.isLiving)
                put("title", ch.title)
                put("sign", ch.sign)
                put("fansCount", ch.fansCount)
                put("followCount", ch.followCount)
                put("totalDanmaku", ch.totalDanmaku)
                put("totalIncome", ch.totalIncome)
                put("liveCount", ch.liveCount)
                put("liveSeconds", ch.liveSeconds)
                put("lastLiveAt", ch.lastLiveAt)
                put("lastStatAt", ch.lastStatAt)
                arr("fansHistory") {
                    ch.fansHistory.forEach { p ->
                        itemObj { put("t", p.at); put("v", p.value) }
                    }
                }
            }
            arr("trend") {
                e.trend.forEach { m ->
                    itemObj {
                        put("month", m.month)
                        put("viewers", m.viewers)
                        put("newViewers", m.newViewers)
                        put("returning", m.returning)
                        put("payers", m.payers)
                        put("guards", m.guards)
                        put("paidYuan", m.paidYuan)
                    }
                }
            }
            e.career?.let { c ->
                obj("career") {
                    put("liveCount", c.liveCount)
                    put("activeDays", c.activeDays)
                    put("liveSeconds", c.liveSeconds)
                    put("danmaku", c.danmaku)
                    put("income", c.income)
                    put("firstLiveAt", c.firstLiveAt)
                    put("lastLiveAt", c.lastLiveAt)
                }
            }
        }.toString()
    }

    fun decodeChannel(text: String): ChannelEntry? {
        val root = parseOrNull(text) ?: return null
        if (root.long("version") > VERSION) return null
        val ch = root.map("channel") ?: return null
        val uid = ch.long("uid")
        if (uid <= 0L) return null
        val fans = ch.list("fansHistory").mapNotNull { raw ->
            val o = raw as? Map<*, *> ?: return@mapNotNull null
            val at = o.long("t")
            if (at <= 0L) null else FansPoint(at, o.int("v"))
        }.sortedBy { it.at }
        val trend = root.list("trend").mapNotNull { raw ->
            val o = raw as? Map<*, *> ?: return@mapNotNull null
            TrendMonth(
                month = o.str("month"),
                viewers = o.int("viewers"),
                newViewers = o.int("newViewers"),
                returning = o.int("returning"),
                payers = o.int("payers"),
                guards = o.int("guards"),
                paidYuan = o.dbl("paidYuan")
            )
        }.sortedBy { it.month }
        val career = root.map("career")?.let { c ->
            CareerStats(
                liveCount = c.int("liveCount"),
                activeDays = c.int("activeDays"),
                liveSeconds = c.long("liveSeconds"),
                danmaku = c.long("danmaku"),
                income = c.dbl("income"),
                firstLiveAt = c.str("firstLiveAt"),
                lastLiveAt = c.str("lastLiveAt")
            )
        }
        return ChannelEntry(
            savedAt = root.long("savedAt"),
            channel = ChannelStats(
                uid = uid,
                name = ch.str("name"),
                roomId = ch.long("roomId"),
                faceUrl = ch.str("faceUrl"),
                isLiving = ch.bool("isLiving"),
                title = ch.str("title"),
                sign = ch.str("sign"),
                fansCount = ch.int("fansCount"),
                followCount = ch.int("followCount"),
                totalDanmaku = ch.long("totalDanmaku"),
                totalIncome = ch.dbl("totalIncome"),
                liveCount = ch.long("liveCount"),
                liveSeconds = ch.long("liveSeconds"),
                lastLiveAt = ch.long("lastLiveAt"),
                lastStatAt = ch.long("lastStatAt"),
                fansHistory = fans
            ),
            trend = trend,
            career = career
        )
    }

    // ── 用户分析结果 ────────────────────────────────────────────────────

    fun encodeAnalysis(e: AnalysisEntry): String {
        val d = e.data
        return JsonWriter().obj {
            put("version", VERSION)
            put("userId", e.userId)
            put("rangeDays", e.rangeDays)
            put("savedAt", e.savedAt)
            put("fromMs", e.fromMs)
            put("toMs", e.toMs)
            arr("names") { d.names.forEach { item(it) } }
            obj("summary") {
                put("messages", d.summary.messages)
                put("interactions", d.summary.interactions)
                put("payment", d.summary.payment)
                put("blockCount", d.summary.blockCount)
                put("activeDays", d.summary.activeDays)
                put("appearanceLives", d.summary.appearanceLives)
                put("interactiveLives", d.summary.interactiveLives)
                put("paidLives", d.summary.paidLives)
            }
            d.insights?.let { i ->
                obj("insights") {
                    put("repeatIndex", i.repeatIndex)
                    put("maxRepeatedText", i.maxRepeatedText)
                    put("maxRepeatedCount", i.maxRepeatedCount)
                    put("emojiRatio", i.emojiRatio)
                    put("nightOwlRatio", i.nightOwlRatio)
                    put("avgLength", i.avgLength)
                    put("uniqueRatio", i.uniqueRatio)
                }
            }
            arr("days") {
                d.days.forEach { x ->
                    itemObj {
                        put("date", x.date)
                        put("messages", x.messages)
                        put("interactions", x.interactions)
                        put("payment", x.payment)
                        put("lives", x.lives)
                    }
                }
            }
            arr("eventTypes") {
                d.eventTypes.forEach { x ->
                    itemObj {
                        put("name", x.name)
                        put("count", x.count)
                        put("payment", x.payment)
                    }
                }
            }
            arr("affinities") {
                d.affinities.forEach { x ->
                    itemObj {
                        put("uid", x.uid)
                        put("name", x.name)
                        put("roomId", x.roomId)
                        put("faceUrl", x.faceUrl)
                        put("messages", x.messages)
                        put("payment", x.payment)
                        put("activeDays", x.activeDays)
                        put("liveCount", x.liveCount)
                        put("loyalty", x.loyalty)
                    }
                }
            }
            arr("topPayments") {
                d.topPayments.forEach { x ->
                    itemObj {
                        put("name", x.name)
                        put("amount", x.amount)
                        put("channelName", x.channelName)
                        put("timestamp", x.timestamp)
                    }
                }
            }
            arr("areas") {
                d.areas.forEach { x ->
                    itemObj { put("name", x.name); put("count", x.count) }
                }
            }
            arr("rhythm") {
                d.rhythm.forEach { row ->
                    arr {
                        row.forEach { item(it) }
                    }
                }
            }
            arr("wordCloud") {
                d.wordCloud.forEach { (k, v) ->
                    arr { item(k); item(v) }
                }
            }
        }.toString()
    }

    fun decodeAnalysis(text: String): AnalysisEntry? {
        val root = parseOrNull(text) ?: return null
        if (root.long("version") > VERSION) return null
        val userId = root.long("userId")
        val days = root.int("rangeDays")
        if (userId <= 0L || days <= 0) return null
        val summary = root.map("summary") ?: return null
        val insights = root.map("insights")?.let { i ->
            Insights(
                repeatIndex = i.dbl("repeatIndex"),
                maxRepeatedText = i.str("maxRepeatedText"),
                maxRepeatedCount = i.int("maxRepeatedCount"),
                emojiRatio = i.dbl("emojiRatio"),
                nightOwlRatio = i.dbl("nightOwlRatio"),
                avgLength = i.dbl("avgLength"),
                uniqueRatio = i.dbl("uniqueRatio")
            )
        }
        return AnalysisEntry(
            userId = userId,
            rangeDays = days,
            savedAt = root.long("savedAt"),
            fromMs = root.long("fromMs"),
            toMs = root.long("toMs"),
            data = UserAnalysis(
                names = root.list("names").mapNotNull { it as? String },
                summary = Summary(
                    messages = summary.int("messages"),
                    interactions = summary.int("interactions"),
                    payment = summary.dbl("payment"),
                    blockCount = summary.int("blockCount"),
                    activeDays = summary.int("activeDays"),
                    appearanceLives = summary.int("appearanceLives"),
                    interactiveLives = summary.int("interactiveLives"),
                    paidLives = summary.int("paidLives")
                ),
                insights = insights,
                days = root.list("days").mapNotNull { raw ->
                    val o = raw as? Map<*, *> ?: return@mapNotNull null
                    DayStat(
                        date = o.str("date"),
                        messages = o.int("messages"),
                        interactions = o.int("interactions"),
                        payment = o.dbl("payment"),
                        lives = o.int("lives")
                    )
                }.sortedBy { it.date },
                eventTypes = root.list("eventTypes").mapNotNull { raw ->
                    val o = raw as? Map<*, *> ?: return@mapNotNull null
                    EventTypeStat(o.str("name"), o.int("count"), o.dbl("payment"))
                }.sortedByDescending { it.count },
                affinities = root.list("affinities").mapNotNull { raw ->
                    val o = raw as? Map<*, *> ?: return@mapNotNull null
                    Affinity(
                        uid = o.long("uid"),
                        name = o.str("name"),
                        roomId = o.long("roomId"),
                        faceUrl = o.str("faceUrl"),
                        messages = o.int("messages"),
                        payment = o.dbl("payment"),
                        activeDays = o.int("activeDays"),
                        liveCount = o.int("liveCount"),
                        loyalty = o.dbl("loyalty")
                    )
                }.sortedByDescending { it.loyalty },
                topPayments = root.list("topPayments").mapNotNull { raw ->
                    val o = raw as? Map<*, *> ?: return@mapNotNull null
                    Payment(
                        name = o.str("name"),
                        amount = o.dbl("amount"),
                        channelName = o.str("channelName"),
                        timestamp = o.long("timestamp")
                    )
                }.sortedByDescending { it.amount },
                areas = root.list("areas").mapNotNull { raw ->
                    val o = raw as? Map<*, *> ?: return@mapNotNull null
                    AreaItem(o.str("name"), o.int("count"))
                },
                rhythm = root.list("rhythm").map { row ->
                    (row as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
                },
                wordCloud = root.list("wordCloud").mapNotNull { raw ->
                    val pair = raw as? List<*> ?: return@mapNotNull null
                    val k = pair.getOrNull(0) as? String ?: return@mapNotNull null
                    val v = (pair.getOrNull(1) as? Number)?.toInt() ?: return@mapNotNull null
                    k to v
                }
            )
        )
    }

    private fun parseOrNull(text: String): Map<*, *>? = try {
        MiniJson.parse(text) as? Map<*, *>
    } catch (t: Throwable) {
        null
    }
}

/**
 * 缓存的落盘位置。
 *
 * 一个主播一个文件、一次分析一个文件：读写只碰自己那一份，
 * 不需要维护一个大 JSON 的索引，也不会因为某一条坏掉让整页数据都读不出来。
 */
class StatsCacheStore(dir: File) {

    private val root = File(dir, "stats-cache")

    private fun channelFile(uid: Long) = File(root, "channel-$uid.json")
    private fun analysisFile(uid: Long, days: Int) = File(root, "analysis-$uid-$days.json")

    fun readChannel(uid: Long): StatsCache.ChannelEntry? = runCatching {
        val f = channelFile(uid)
        if (!f.isFile) null else StatsCache.decodeChannel(f.readText())
    }.getOrNull()

    fun writeChannel(e: StatsCache.ChannelEntry) {
        write(channelFile(e.channel.uid), StatsCache.encodeChannel(e))
        prune()
    }

    fun readAnalysis(uid: Long, days: Int): StatsCache.AnalysisEntry? = runCatching {
        val f = analysisFile(uid, days)
        if (!f.isFile) null else StatsCache.decodeAnalysis(f.readText())
    }.getOrNull()

    fun writeAnalysis(e: StatsCache.AnalysisEntry) {
        write(analysisFile(e.userId, e.rangeDays), StatsCache.encodeAnalysis(e))
        prune()
    }

    /** 写临时文件再改名：中途被杀不会留下半截 JSON 让下次读崩 */
    private fun write(target: File, text: String) {
        runCatching {
            if (!root.isDirectory && !root.mkdirs()) return
            val tmp = File(root, target.name + ".tmp")
            tmp.writeText(text)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                // 个别 ROM 上 rename 会失败，退回直接覆盖
                target.writeText(text)
                tmp.delete()
            }
        }
    }

    /**
     * 缓存是「顺手用」的，不能无限长大。只保留最近各 N 个文件，
     * 其余按修改时间淘汰——用户常看的主播就那么几个，更早的重新拉一次也不贵。
     */
    fun prune(keepChannels: Int = 20, keepAnalyses: Int = 10) {
        runCatching {
            val files = root.listFiles() ?: return
            files.filter { it.name.startsWith("channel-") && it.name.endsWith(".json") }
                .sortedByDescending { it.lastModified() }
                .drop(keepChannels)
                .forEach { it.delete() }
            files.filter { it.name.startsWith("analysis-") && it.name.endsWith(".json") }
                .sortedByDescending { it.lastModified() }
                .drop(keepAnalyses)
                .forEach { it.delete() }
        }
    }
}

// ── 解析辅助：值缺失或类型不符时退回默认值，不让整个缓存文件作废 ──

private fun Map<*, *>.map(key: String): Map<*, *>? = this[key] as? Map<*, *>
private fun Map<*, *>.list(key: String): List<*> = this[key] as? List<*> ?: emptyList<Any?>()
private fun Map<*, *>.str(key: String): String = (this[key] as? String) ?: ""
private fun Map<*, *>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
private fun Map<*, *>.int(key: String): Int = (this[key] as? Number)?.toInt() ?: 0
private fun Map<*, *>.dbl(key: String): Double = (this[key] as? Number)?.toDouble() ?: 0.0
private fun Map<*, *>.bool(key: String): Boolean = (this[key] as? Boolean) ?: false
