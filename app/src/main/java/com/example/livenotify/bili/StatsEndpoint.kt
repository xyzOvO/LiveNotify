package com.example.livenotify.bili

/**
 * B 站数据接口的线路选择。纯 Kotlin、不依赖 Android，方便把降级规则锁进单元测试。
 *
 * 域名取自 danmakus 采集端公开的 BACKEND_API_ORIGINS，实测前两个域对同一请求
 * 返回完全一致的数据；第三个 api.danmakus.com 在部分网络下不可达，暂不写死。
 */
internal object StatsEndpoint {

    const val PRIMARY = "https://ukamnads.icu"

    val ORIGINS: List<String> = listOf(PRIMARY, "https://api.ukamnads.icu")

    /** 上次成功的域排在最前，其余按内置顺序补齐；未知/非法值一律忽略 */
    fun ordered(preferred: String?): List<String> {
        val chain = ArrayList<String>(ORIGINS.size)
        if (preferred != null && preferred in ORIGINS) chain += preferred
        for (origin in ORIGINS) {
            if (origin !in chain) chain += origin
        }
        return chain
    }

    /**
     * 只替换主域前缀。以后如果接了别的域名（例如 B 站官方接口），原样放行，
     * 避免把无关请求也改写到备用节点上。
     */
    fun rewrite(url: String, primary: String, origin: String): String =
        if (origin == primary || !url.startsWith(primary)) url
        else origin + url.removePrefix(primary)

    fun label(origin: String): String = if (origin == PRIMARY) "主节点" else "备用节点"

    /** 接口文档与当前线路同源：主域挂掉时备用域的 swagger 同样可用 */
    fun docsUrl(origin: String): String = "$origin/swagger/index.html"
}
