package com.example.livenotify.update

/**
 * GitHub Release 的「稳定版」判定口径。
 *
 * GitHub 的 /releases/latest 按文档只返回最新非 draft、非 prerelease 的发布，
 * 但部分镜像/代理或异常缓存可能塞进预发布；显式再判一次，避免把内测包装给正式用户。
 * 抽成纯函数便于 JVM 单测（org.json 在单测 classpath 里只有 stub）。
 */
internal object StableRelease {
    /**
     * @param draft 是否草稿
     * @param prerelease 是否预发布
     * @return true 表示可作为正式更新推送
     */
    fun isStable(draft: Boolean, prerelease: Boolean): Boolean = !draft && !prerelease
}
