package com.example.livenotify.util

/**
 * 从「分享文案」中提取可解析的目标。
 *
 * 各平台 App 的分享文案常带大量中文，例如：
 *   「【七海Nana7mi的直播间】https://live.bilibili.com/21452505 快来围观～」
 *   「复制打开抖音，看看【xx的作品】https://live.douyin.com/629458933」
 *   「来我直播间吧！房间号21452505」
 *
 * 提取优先级：
 *   1. 完整 URL（含协议）—— 分享文案里最常见、也最可靠
 *   2. 无协议的域名形式 —— live.douyin.com/629458933
 *   3. 最长的连续数字串 —— 房间号 / web_rid 被中文夹住的情况
 *
 * 输入不含 CJK / 全角字符时原样返回（本身就是干净输入）。
 */
object ShareText {

    /** 是否包含 CJK 或全角标点（U+2E80 起：CJK 部首、全角符号、【】「」等） */
    private fun hasCJK(s: String): Boolean = s.any { it.code > 0x2E80 }

    fun extract(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty() || !hasCJK(s)) return s

        // 1. 完整 URL。URL 字符集里不含空白与 CJK，天然在中文处截断
        Regex("""https?://[A-Za-z0-9\-._~:/?#@!$&*+,;=%]+""").find(s)?.let { return it.value }

        // 2. 无协议域名形式：live.douyin.com/629458933、space.bilibili.com/672328094
        Regex("""(?:[A-Za-z0-9-]+\.)+(?:com|cn|tv|net)(?:/[A-Za-z0-9_./-]+)?""").find(s)?.let { return it.value }

        // 3. 被中文夹住的房间号 / web_rid：取最长数字串（3~12 位，与各平台 resolve 上限一致）
        Regex("""\d{3,12}""").findAll(s).maxByOrNull { it.value.length }?.let { return it.value }

        return s
    }
}
