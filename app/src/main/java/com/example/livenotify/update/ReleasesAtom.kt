package com.example.livenotify.update

/**
 * Release Atom 源的兜底解析。
 *
 * 为什么要它：`api.github.com` 在部分网络 / 共享出口 IP 下会直接 403（限流或屏蔽），
 * 而 `github.com/.../releases.atom` 是另一套服务，通常还能访问。
 * 只在 API 路径失败时用来补「更新日志」，解析失败就返回空串，不影响更新本身。
 */
object ReleasesAtom {

    private val ENTRY = Regex("""<entry>([\s\S]*?)</entry>""")
    private val TITLE = Regex("""<title>\s*([^<]*?)\s*</title>""")
    private val CONTENT = Regex("""<content type="html">([\s\S]*?)</content>""")

    /** 取指定 tag 的更新说明；找不到返回空串 */
    fun notesForTag(xml: String, tag: String): String {
        if (xml.isBlank() || tag.isBlank()) return ""
        val entry = ENTRY.findAll(xml).firstOrNull { m ->
            TITLE.find(m.groupValues[1])?.groupValues?.get(1)?.trim() == tag
        } ?: return ""
        val raw = CONTENT.find(entry.groupValues[1])?.groupValues?.get(1) ?: return ""
        return htmlToText(unescapeXml(raw))
    }

    /** Atom 里的 content 是 XML 转义过的 HTML，先还原成 HTML */
    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        // &amp; 必须最后处理，否则 &amp;lt; 会被解成 <
        .replace("&amp;", "&")

    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val LI_OPEN = Regex("""<li[^>]*>""", RegexOption.IGNORE_CASE)
    private val BLOCK_END = Regex("""</(p|li|ul|ol|h[1-6]|div|blockquote|pre|tr)>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")

    private fun htmlToText(html: String): String {
        val text = html
            .replace(BR, "\n")
            .replace(LI_OPEN, "· ")
            .replace(BLOCK_END, "\n")
            .replace(TAG, "")
        return decodeEntities(text)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun decodeEntities(s: String): String {
        var out = s
            .replace("&nbsp;", " ")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        // 剩下形如 &#20013; 的数字实体
        out = Regex("""&#(\d+);""").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        return out
    }
}
