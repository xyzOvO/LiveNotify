package com.example.livenotify

import com.example.livenotify.update.ReleasesAtom
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * api.github.com 被 403 时，更新日志靠 Atom 源兜底。
 * 这里的样本按 GitHub 实际返回的结构手写（XML 里嵌着转义过的 HTML）。
 */
class ReleasesAtomTest {

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Release notes from LiveNotify</title>
          <entry>
            <id>tag:github.com,2008:Repository/1/v1.0.10</id>
            <title>v1.0.10</title>
            <content type="html">&lt;h2&gt;新增&lt;/h2&gt;
        &lt;ul&gt;
        &lt;li&gt;&lt;strong&gt;分享导入&lt;/strong&gt;：从别的 App 分享进来&lt;/li&gt;
        &lt;li&gt;暂停 / 恢复关注&lt;/li&gt;
        &lt;/ul&gt;
        &lt;p&gt;修复若干问题 &amp;amp; 优化&lt;/p&gt;</content>
          </entry>
          <entry>
            <title>v1.0.9</title>
            <content type="html">&lt;p&gt;旧版本说明&lt;/p&gt;</content>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `按 tag 找到对应条目并转成纯文本`() {
        val notes = ReleasesAtom.notesForTag(feed, "v1.0.10")
        assertEquals(
            listOf("新增", "· 分享导入：从别的 App 分享进来", "· 暂停 / 恢复关注", "修复若干问题 & 优化"),
            notes.lines()
        )
    }

    @Test
    fun `取的是指定 tag 而不是最新一条`() {
        assertEquals("旧版本说明", ReleasesAtom.notesForTag(feed, "v1.0.9"))
    }

    @Test
    fun `找不到 tag 返回空串`() {
        assertEquals("", ReleasesAtom.notesForTag(feed, "v9.9.9"))
    }

    @Test
    fun `源内容异常时返回空串而不是崩溃`() {
        assertEquals("", ReleasesAtom.notesForTag("", "v1.0.10"))
        assertEquals("", ReleasesAtom.notesForTag("<feed></feed>", "v1.0.10"))
        assertEquals("", ReleasesAtom.notesForTag(feed, ""))
    }

    @Test
    fun `数字实体被还原`() {
        val xml = """
            <feed><entry><title>v1.0.1</title>
            <content type="html">&lt;p&gt;&amp;#20013;&amp;#25991;&lt;/p&gt;</content>
            </entry></feed>
        """.trimIndent()
        assertEquals("中文", ReleasesAtom.notesForTag(xml, "v1.0.1"))
    }
}
