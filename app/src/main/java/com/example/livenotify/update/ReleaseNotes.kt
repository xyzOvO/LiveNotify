package com.example.livenotify.update

/**
 * 把 GitHub Release 的 Markdown 正文压成适合小屏展示的纯文本。
 *
 * 不引 Markdown 库：更新说明的结构就那几种（标题 / 列表 / 加粗 / 链接），
 * 而弹窗里出现 `###` `**` 这类符号比不显示还难看。这里只做去噪，不做排版。
 */
object ReleaseNotes {

    /** 正文可以很长，截断时保留整行，避免把一句话切成两半 */
    const val DEFAULT_MAX = 3000

    fun format(raw: String, maxChars: Int = DEFAULT_MAX): String {
        if (raw.isBlank()) return ""

        val kept = ArrayList<String>(24)
        // GitHub 自动生成的「Full Changelog」链接对普通用户没有意义
        val dropLine = Regex("""^\s*(\*\*)?full changelog(\*\*)?\s*:""", RegexOption.IGNORE_CASE)
        val htmlComment = Regex("""<!--[\s\S]*?-->""")
        val image = Regex("""!\[[^\]]*]\([^)]*\)""")
        val link = Regex("""\[([^\]]+)]\([^)]*\)""")

        val body = raw.replace(htmlComment, "").replace("\r\n", "\n").replace('\r', '\n')
        for (line in body.split('\n')) {
            var s = line.trim()
            if (s.isEmpty()) {
                // 连续空行只留一个，后面统一收尾
                if (kept.isNotEmpty() && kept.last().isNotEmpty()) kept.add("")
                continue
            }
            if (dropLine.containsMatchIn(s)) continue

            s = image.replace(s, "")
            s = link.replace(s, "$1")
            // 标题标记：### 新增 → 新增
            s = Regex("""^#{1,6}\s*""").replace(s, "")
            // 列表：- / * / + → ·
            s = Regex("""^[-*+]\s+""").replace(s, "· ")
            // 引用与分隔线
            s = Regex("""^>\s*""").replace(s, "")
            s = Regex("""^[-*_]{3,}$""").replace(s, "")
            // 行内强调与代码
            s = s.replace("**", "").replace("__", "").replace("`", "")
            s = s.trim()
            if (s.isEmpty()) continue
            kept.add(s)
        }

        // 收尾去空行
        while (kept.isNotEmpty() && kept.last().isEmpty()) kept.removeAt(kept.size - 1)
        val text = kept.joinToString("\n")
        if (text.length <= maxChars) return text

        // 按行截断，宁可少一行也不要半句
        var cut = 0
        for (line in text.lineSequence()) {
            val next = cut + line.length + 1
            if (next > maxChars) break
            cut = next
        }
        if (cut <= 0) return text.take(maxChars) + "…"
        return text.take(cut).trimEnd() + "\n…"
    }
}
