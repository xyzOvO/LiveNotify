package com.example.livenotify.util

/**
 * 从 WebView 抓到的抖音 Cookie 里识别登录态、合并多来源 Cookie。
 *
 * 纯函数，不碰 Android API，方便 JVM 单测把「合并去重 / 登录判定」这些
 * 容易出错的逻辑锁死。WebView 里 CookieManager.getCookie(url) 只会返回
 * 该 URL 域下的 Cookie，所以要分别从 live/www/passport 三个域读再合并。
 */
object DouyinCookieCapture {
    /** 登录态标志：任一存在即认为已登录。ttwid 只是设备指纹，没登录也会有，不算。 */
    private val SESSION_MARKERS = listOf("sessionid", "sessionid_ss", "sid_tt", "sid_guard")

    /** 抓取来源 URL：抖音登录态 Cookie 大多 domain=.douyin.com，三域合并更全 */
    val SOURCE_URLS = listOf(
        "https://live.douyin.com/",
        "https://www.douyin.com/",
        "https://passport.douyin.com/"
    )

    /**
     * 合并多个「name=value; ...」串；同名后者覆盖前者，保留首次出现顺序。
     * 忽略空段和没有 = 的段。
     */
    fun mergeCookies(chunks: List<String>): String {
        val map = LinkedHashMap<String, String>()
        for (chunk in chunks) {
            for (part in chunk.split(";")) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val name = part.substring(0, idx).trim()
                if (name.isEmpty()) continue
                map[name] = part.substring(idx + 1).trim()
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 是否含登录态标志 */
    fun hasLoginSession(cookie: String): Boolean {
        val names = cookie.split(";").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx > 0) part.substring(0, idx).trim() else null
        }.toSet()
        return SESSION_MARKERS.any { it in names }
    }

    /** Cookie 串里 name=value 的对数，用于界面显示「抓到 N 项」 */
    fun count(cookie: String): Int = cookie.split(";").count { part ->
        val idx = part.indexOf('=')
        idx > 0 && part.substring(0, idx).trim().isNotEmpty()
    }

    /**
     * Cookie 保存后的提示文案。加密失败（saved=false）时必须明说没保存，
     * 不能沿用「已保存」误导用户以为凭据已经安全落盘。
     */
    fun saveMessage(inputBlank: Boolean, saved: Boolean): String = when {
        inputBlank -> "已清空 Cookie"
        saved -> "Cookie 已保存到本机"
        else -> "Cookie 加密失败，未保存（旧 Cookie 已保留）"
    }
}
