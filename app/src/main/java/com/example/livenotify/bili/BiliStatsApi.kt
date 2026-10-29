package com.example.livenotify.bili

import android.util.Log
import com.example.livenotify.net.RateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * B 站主播 / 用户数据接口（第三方公开数据服务，只读）。
 *
 * 用的是 danmakus 的公开 API：B 站官方并不提供「粉丝历史」「观众月度趋势」
 * 「用户互动画像」这类数据，这些是社区长期采集后聚合出来的。
 *
 * 全部 GET/POST 均无需鉴权，也没有携带任何账号信息——只在请求里传 uid，
 * 不发送本机关注列表以外的任何内容。
 */
object BiliStatsApi {

    private const val TAG = "BiliStatsApi"

    /** 接口根地址。域名若变更只改这一处。 */
    const val BASE = "https://ukamnads.icu"

    /** 展示给用户的数据来源说明 */
    const val SOURCE_NOTE = "数据来自 danmakus.com 的公开统计（社区采集，非 B 站官方）"

    private val limiter = RateLimiter(800L)

    /**
     * 分析类请求要等服务端扫历史场次，耗时明显长于普通查询，
     * 因此用独立的客户端，超时放宽（但仍设上限，避免界面卡死）。
     */
    private val slowClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(2, 3, TimeUnit.MINUTES))
            .build()
    }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    // ── 对外接口 ────────────────────────────────────────────────────────

    /** 主播概览 + 粉丝走势（一次请求拿到，含粉丝总数与历史点位） */
    suspend fun channel(uid: Long): ChannelStats {
        val text = getText("$BASE/api/v2/channel?uId=$uid&includeLive=false&includeExtra=false")
        val root = JSONObject(text)
        ensureOk(root, "主播数据")
        val data = root.optJSONObject("data") ?: throw BiliApiException("主播数据为空")
        val ch = data.optJSONObject("channel") ?: throw BiliApiException("没有收录该主播的数据")
        val fans = ArrayList<FansPoint>()
        data.optJSONArray("fansHistory")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optLong("t", 0L)
                val v = o.optInt("v", 0)
                if (t > 0L) fans.add(FansPoint(t, v))
            }
        }
        return ChannelStats(
            uid = ch.optLong("uId", uid),
            name = ch.text("uName"),
            roomId = ch.optLong("roomId", 0L),
            faceUrl = ch.text("faceUrl"),
            isLiving = ch.optBoolean("isLiving", false),
            title = ch.text("title"),
            sign = ch.text("sign"),
            fansCount = ch.optInt("fansCount", 0),
            followCount = ch.optInt("followCount", 0),
            totalDanmaku = ch.optLong("totalDanmakuCount", 0L),
            totalIncome = ch.optDouble("totalIncome", 0.0),
            liveCount = ch.optLong("totalLiveCount", 0L),
            liveSeconds = ch.optLong("totalLiveSecond", 0L),
            lastLiveAt = ch.optLong("lastLiveDate", 0L),
            fansHistory = fans.sortedBy { it.at }
        )
    }

    /** 月度互动观众趋势：观众数 / 新观众 / 回访 / 付费人数 / 上舰 / 打赏金额 */
    suspend fun audienceTrend(uid: Long): List<TrendMonth> {
        val text = getText("$BASE/api/v2/channel/audience-trend?uId=$uid")
        val root = JSONObject(text)
        ensureOk(root, "观众趋势")
        val months = root.optJSONObject("data")?.optJSONArray("months") ?: return emptyList()
        val out = ArrayList<TrendMonth>(months.length())
        for (i in 0 until months.length()) {
            val o = months.optJSONObject(i) ?: continue
            out.add(
                TrendMonth(
                    month = o.text("month"),
                    viewers = o.optInt("viewers", 0),
                    newViewers = o.optInt("newViewers", 0),
                    returning = o.optInt("returningViewers", 0),
                    payers = o.optInt("payers", 0),
                    guards = o.optInt("guardBuyers", 0),
                    paidYuan = o.optLong("paidMilli", 0L) / 1000.0
                )
            )
        }
        return out.sortedBy { it.month }
    }

    /** 生涯统计（总场次 / 活跃天数 / 总时长 / 弹幕总量 / 总收入） */
    suspend fun career(uid: Long): CareerStats? {
        val text = getText("$BASE/api/v2/wiki/channel/$uid/career")
        val root = JSONObject(text)
        if (root.optInt("code", 0) != 200) return null
        val overall = root.optJSONObject("data")?.optJSONObject("overall") ?: return null
        return CareerStats(
            liveCount = overall.optInt("liveCount", 0),
            activeDays = overall.optInt("activeDays", 0),
            liveSeconds = overall.optLong("liveDurationSeconds", 0L),
            danmaku = overall.optLong("danmakuCount", 0L),
            income = overall.optDouble("totalIncome", 0.0),
            firstLiveAt = overall.text("firstLiveAt"),
            lastLiveAt = overall.text("lastLiveAt")
        )
    }

    /**
     * 分析某个用户在直播间的互动行为。
     *
     * 服务端是异步任务：创建 → 轮询状态 → 取结果（MessagePack）。
     * [onProgress] 会收到 0~100 的进度与阶段文案。
     */
    suspend fun analyze(
        userId: Long,
        fromMs: Long,
        toMs: Long,
        onProgress: (percent: Int, message: String) -> Unit
    ): UserAnalysis {
        val body = JSONObject()
            .put("userId", userId)
            .put("startTime", fromMs)
            .put("endTime", toMs)
            .toString()

        // 服务端偶尔会对完全合法的请求回 400（重试一次基本就好），
        // 这里只包住「创建任务」这一步，轮询阶段失败不重试，避免重复扫库。
        val created = try {
            postText("$BASE/api/v3/user-analysis-jobs", body)
        } catch (e: BiliApiException) {
            delay(1200)
            postText("$BASE/api/v3/user-analysis-jobs", body)
        }
        val jobId = JSONObject(created).text("jobId")
        if (jobId.isBlank()) throw BiliApiException("分析任务创建失败")

        // 轮询：服务端实测 3 秒左右完成，这里给到约 40 秒的容忍度
        var status = ""
        for (attempt in 0 until 45) {
            delay(1000)
            val st = JSONObject(getText("$BASE/api/v3/user-analysis-jobs/$jobId"))
            status = st.text("status")
            val ev = st.optJSONObject("latestEvent")
            val p = ev?.optDouble("progress", 0.0)?.toInt() ?: 0
            val msg = ev?.text("message").orEmpty()
            val loaded = ev?.optInt("loadedLives", 0) ?: 0
            val total = ev?.optInt("totalLives", 0) ?: 0
            onProgress(
                p.coerceIn(0, 100),
                when {
                    total > 0 -> "$msg $loaded/$total 场"
                    msg.isNotBlank() -> msg
                    else -> "分析中…"
                }
            )
            if (status.equals("completed", true)) break
            if (status.equals("failed", true) || status.equals("canceled", true) ||
                status.equals("cancelled", true)
            ) {
                throw BiliApiException("分析失败" + if (msg.isNotBlank()) "：$msg" else "")
            }
        }
        if (!status.equals("completed", true)) throw BiliApiException("分析用时过长，把时间范围调小一点再试")

        onProgress(100, "整理结果…")
        val bytes = getBytes("$BASE/api/v3/user-analysis-jobs/$jobId/result")
        val root = MsgPack.decode(bytes).asMap() ?: throw BiliApiException("结果解析失败")
        return parseAnalysis(root)
    }

    // ── 解析 ────────────────────────────────────────────────────────────

    internal fun parseAnalysis(root: Map<String, Any?>): UserAnalysis {
        val summaryMap = root["summary"].asMap()
        val insightMap = root["insights"].asMap()

        val wordCloud = LinkedHashMap<String, Int>()
        (root["wordCloud"] as? Map<*, *>)?.forEach { (k, v) ->
            val key = k?.toString() ?: return@forEach
            val n = (v as? Long)?.toInt() ?: (v as? Double)?.toInt() ?: 0
            if (key.isNotBlank() && n > 0) wordCloud[key] = n
        }

        val eventTypes = ArrayList<EventTypeStat>()
        (root["eventTypes"] as? Map<*, *>)?.forEach { (k, v) ->
            val name = k?.toString() ?: return@forEach
            val m = v.asMap() ?: return@forEach
            val count = m.int("count") ?: 0
            if (count <= 0) return@forEach
            eventTypes.add(EventTypeStat(name, count, m.double("payment") ?: 0.0))
        }

        return UserAnalysis(
            names = root.list("nameHistory").mapNotNull { it as? String },
            summary = Summary(
                messages = summaryMap?.int("totalMessages") ?: 0,
                interactions = summaryMap?.int("totalInteractions") ?: 0,
                payment = summaryMap?.double("totalPayment") ?: 0.0,
                blockCount = summaryMap?.int("blockCount") ?: 0,
                activeDays = summaryMap?.int("activeDays") ?: 0,
                appearanceLives = summaryMap?.int("appearanceLives") ?: 0,
                interactiveLives = summaryMap?.int("interactiveLives") ?: 0,
                paidLives = summaryMap?.int("paidLives") ?: 0
            ),
            insights = if (insightMap == null) null else Insights(
                repeatIndex = insightMap.double("repeaterIndex") ?: 0.0,
                maxRepeatedText = insightMap.str("maxRepeatedText"),
                maxRepeatedCount = insightMap.int("maxRepeatedCount") ?: 0,
                emojiRatio = insightMap.double("emojiRatio") ?: 0.0,
                nightOwlRatio = insightMap.double("nightOwlRatio") ?: 0.0,
                avgLength = insightMap.double("avgMessageLength") ?: 0.0,
                uniqueRatio = insightMap.double("uniqueMessageRatio") ?: 0.0
            ),
            days = root.list("activityDays").mapNotNull { it.asMap() }.map {
                DayStat(
                    date = it.str("date"),
                    messages = it.int("messages") ?: 0,
                    interactions = it.int("interactions") ?: 0,
                    payment = it.double("payment") ?: 0.0,
                    lives = it.int("lives") ?: 0
                )
            }.sortedBy { it.date },
            eventTypes = eventTypes.sortedByDescending { it.count },
            affinities = root.list("channelAffinities").mapNotNull { it.asMap() }.map {
                Affinity(
                    uid = it.long("channelUid") ?: 0L,
                    name = it.str("channelName"),
                    roomId = it.long("roomId") ?: 0L,
                    faceUrl = it.str("faceUrl"),
                    messages = it.int("messageCount") ?: 0,
                    payment = it.double("payment") ?: 0.0,
                    activeDays = it.int("activeDays") ?: 0,
                    liveCount = it.int("liveCount") ?: 0,
                    loyalty = it.double("loyaltyScore") ?: 0.0
                )
            }.sortedByDescending { it.loyalty },
            topPayments = root.list("topPayments").mapNotNull { it.asMap() }.map {
                Payment(
                    // 服务端经常不给 name（只给金额），用事件类型兜底，别在界面上留一片横线
                    name = it.str("name").ifBlank { paymentLabel(it.int("type")) },
                    amount = it.double("amount") ?: 0.0,
                    channelName = it.str("channelName"),
                    timestamp = it.long("timestamp") ?: 0L
                )
            }.sortedByDescending { it.amount },
            areas = root.list("areas").mapNotNull { it.asMap() }.map {
                AreaItem(it.str("name"), it.int("count") ?: 0)
            },
            rhythm = root.list("rhythmMatrix").map { row ->
                (row as? List<Any?>)?.map { (it as? Long)?.toInt() ?: 0 } ?: emptyList()
            },
            wordCloud = wordCloud.entries.sortedByDescending { it.value }.take(40)
                .map { it.key to it.value }
        )
    }

    // ── HTTP ────────────────────────────────────────────────────────────

    private fun ensureOk(root: JSONObject, what: String) {
        val code = root.optInt("code", 0)
        if (code != 200) {
            val msg = root.text("message").ifBlank { "code=$code" }
            throw BiliApiException("$what 获取失败：$msg")
        }
    }

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        limiter.pass()
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        try {
            slowClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw BiliApiException(httpError(resp.code, resp.body?.string()))
                resp.body?.string() ?: throw BiliApiException("空响应")
            }
        } catch (e: BiliApiException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "GET $url failed: ${t.message}")
            throw BiliApiException("网络请求失败，请稍后重试")
        }
    }

    private suspend fun postText(url: String, body: String): String = withContext(Dispatchers.IO) {
        limiter.pass()
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        try {
            slowClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw BiliApiException(httpError(resp.code, resp.body?.string()))
                resp.body?.string() ?: throw BiliApiException("空响应")
            }
        } catch (e: BiliApiException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "POST $url failed: ${t.message}")
            throw BiliApiException("网络请求失败，请稍后重试")
        }
    }

    private suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        limiter.pass()
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/x-msgpack")
            .build()
        try {
            slowClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw BiliApiException(httpError(resp.code, null))
                resp.body?.bytes() ?: throw BiliApiException("空响应")
            }
        } catch (e: BiliApiException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "GET(bytes) $url failed: ${t.message}")
            throw BiliApiException("结果下载失败，请稍后重试")
        }
    }
}

/** 打赏条目没有礼物名时，用事件类型当标题（数值见 B 站 DanmakuType 枚举） */
private fun paymentLabel(type: Int?): String = when (type) {
    0 -> "弹幕"
    1 -> "礼物"
    2 -> "上舰"
    3 -> "醒目留言"
    else -> "打赏"
}

class BiliApiException(message: String) : IOException(message)

// ── 数据模型 ────────────────────────────────────────────────────────────

/** 粉丝历史点位（at 为毫秒时间戳） */
data class FansPoint(val at: Long, val value: Int)

data class ChannelStats(
    val uid: Long,
    val name: String,
    val roomId: Long,
    val faceUrl: String,
    val isLiving: Boolean,
    val title: String,
    val sign: String,
    val fansCount: Int,
    val followCount: Int,
    val totalDanmaku: Long,
    val totalIncome: Double,
    val liveCount: Long,
    val liveSeconds: Long,
    val lastLiveAt: Long,
    val fansHistory: List<FansPoint>
)

data class TrendMonth(
    val month: String,
    val viewers: Int,
    val newViewers: Int,
    val returning: Int,
    val payers: Int,
    val guards: Int,
    val paidYuan: Double
)

data class CareerStats(
    val liveCount: Int,
    val activeDays: Int,
    val liveSeconds: Long,
    val danmaku: Long,
    val income: Double,
    val firstLiveAt: String,
    val lastLiveAt: String
)

data class Summary(
    val messages: Int,
    val interactions: Int,
    val payment: Double,
    val blockCount: Int,
    val activeDays: Int,
    val appearanceLives: Int,
    val interactiveLives: Int,
    val paidLives: Int
)

data class Insights(
    val repeatIndex: Double,
    val maxRepeatedText: String,
    val maxRepeatedCount: Int,
    val emojiRatio: Double,
    val nightOwlRatio: Double,
    val avgLength: Double,
    val uniqueRatio: Double
)

data class DayStat(
    val date: String,
    val messages: Int,
    val interactions: Int,
    val payment: Double,
    val lives: Int
)

data class EventTypeStat(val name: String, val count: Int, val payment: Double)

data class Affinity(
    val uid: Long,
    val name: String,
    val roomId: Long,
    val faceUrl: String,
    val messages: Int,
    val payment: Double,
    val activeDays: Int,
    val liveCount: Int,
    val loyalty: Double
)

data class Payment(
    val name: String,
    val amount: Double,
    val channelName: String,
    val timestamp: Long
)

data class AreaItem(val name: String, val count: Int)

data class UserAnalysis(
    val names: List<String>,
    val summary: Summary,
    val insights: Insights?,
    val days: List<DayStat>,
    val eventTypes: List<EventTypeStat>,
    val affinities: List<Affinity>,
    val topPayments: List<Payment>,
    val areas: List<AreaItem>,
    val rhythm: List<List<Int>>,
    val wordCloud: List<Pair<String, Int>>
)

/**
 * 把服务端的错误原文带出来：只报「HTTP 400」等于什么都没说。
 * 服务端错误体是 `{"message":"无效的日期范围"}` 这种形状，直接把中文取出来。
 */
private fun httpError(code: Int, body: String?): String {
    val detail = body?.trim()?.take(200)?.replace("\n", " ").orEmpty()
    if (code == 429) return "请求太频繁，稍等一会儿再试"
    if (code == 504 || code == 502) return "服务端处理超时，把时间范围调小一点再试"
    if (detail.isBlank()) return "HTTP $code"
    val msg = runCatching { JSONObject(detail).text("message") }.getOrNull()
    return if (!msg.isNullOrBlank()) msg else "HTTP $code：$detail"
}

/** org.json 的 optString 遇 JSON null 会返回字符串 "null"，这里统一抹平 */
private fun JSONObject.text(key: String): String =
    optString(key, "").let { if (it == "null") "" else it }
