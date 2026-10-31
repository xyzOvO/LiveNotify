package com.example.livenotify.net

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** 简单的按 host 串行节流器：保证同一平台两次请求之间留出间隔，避免被限流。 */
class RateLimiter(private val minGapMs: Long) {
    private val mutex = Mutex()
    private var nextSlot = 0L

    suspend fun pass() {
        val wait = mutex.withLock {
            // 用单调时钟计算间隔，避免系统时间被校时回拨后长时间等待。
            val now = SystemClock.elapsedRealtime()
            val w = (nextSlot - now).coerceAtLeast(0L)
            nextSlot = now + w + minGapMs
            w
        }
        if (wait > 0) delay(wait)
    }
}

object Http {
    private const val TAG = "Http"

    const val UA_PC =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    /**
     * 移动端 UA。抖音 webcast 的移动端落地页（webcast.amemv.com/.../reflow/<房间号>）
     * 只接受移动端 UA，PC UA 会直接 404；而该页面正是目前唯一还能拿到直播间状态的地方。
     */
    const val UA_MOBILE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    /** 单平台页面/接口响应上限，避免异常大响应一次性读入内存（无所谓的分块传输也算在内）。 */
    private const val MAX_BODY_BYTES = 2L * 1024 * 1024
    private val UTF_8: Charset = Charset.forName("UTF-8")

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            // 总时限：服务端缓慢持续发数据时，防止单轮检测无限期占住执行链
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // 复用连接，减少 TLS 握手次数 → 省电省流量
            .connectionPool(ConnectionPool(3, 5, TimeUnit.MINUTES))
            .build()
    }

    /**
     * execute() 是阻塞调用，协程取消不会自动打断它。这里把 Call 和当前协程绑起来：
     * 停止监控（或退出页面）时立刻 cancel 掉在途请求，而不是让 IO 线程空等到 20 秒超时，
     * 也避免取消之后拿到的结果被当成「本轮检测成功」继续走通知流程。
     */
    private suspend fun <T> Call.runCancellable(block: (Response) -> T): T {
        val job = coroutineContext[Job]
        val handle = job?.invokeOnCompletion { if (job.isCancelled) cancel() }
        try {
            return execute().use(block)
        } finally {
            handle?.dispose()
        }
    }

    /**
     * 同步执行（调用方已在 IO 线程）。返回 body 字符串，失败抛 IOException。
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cookie: String? = null,
        limiter: RateLimiter? = null,
        ua: String = UA_PC
    ): String = withContext(Dispatchers.IO) {
        limiter?.pass()
        val builder = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
        client.newCall(builder.build()).runCancellable { resp: Response ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} @ $url")
            val body = resp.body ?: throw IOException("空响应 @ $url")
            body.source().use { source ->
                val buf = Buffer()
                var total = 0L
                // okio 的 read(...) 一次最多搬一个缓冲块，必须循环读满，否则响应会被截断。
                while (total <= MAX_BODY_BYTES) {
                    val read = source.read(buf, MAX_BODY_BYTES + 1 - total)
                    if (read <= 0L) break
                    total += read
                }
                if (total > MAX_BODY_BYTES) throw IOException("响应超过 ${MAX_BODY_BYTES / 1024}KB @ $url")
                buf.readString(UTF_8)
            }
        }
    }

    /** 跟随重定向拿到最终 URL，用于解析 v.douyin.com / b23.tv 短链 */
    suspend fun finalUrl(url: String, limiter: RateLimiter? = null, ua: String = UA_PC): String? =
        withContext(Dispatchers.IO) {
            limiter?.pass()
            val req = Request.Builder().url(url).header("User-Agent", ua).build()
            try {
                client.newCall(req).runCancellable { it.request.url.toString() }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "resolve redirect failed: ${t.message}")
                null
            }
        }

    suspend fun getJson(url: String, limiter: RateLimiter? = null): String =
        get(url, mapOf("Accept" to "application/json, text/plain, */*"), null, limiter)
}
