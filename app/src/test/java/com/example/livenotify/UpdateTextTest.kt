package com.example.livenotify

import com.example.livenotify.ui.packageLine
import com.example.livenotify.ui.progressText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 更新界面的两行文案：都只有一句话，但一旦算错就是「3.1 MB / 0 B」这种看着像坏了的东西。
 */
class UpdateTextTest {

    @Test
    fun `有总大小时显示已下载与总量`() {
        assertEquals(
            "正在下载 37% · 1.0 MB / 2.5 MB",
            progressText(37, 1_048_576, 2_621_440)
        )
    }

    @Test
    fun `拿不到总大小时只说百分比`() {
        assertEquals("正在下载 37%", progressText(37, 1_048_576, 0))
        assertEquals("正在下载 0%", progressText(0, 0, 0))
    }

    @Test
    fun `知道包大小才显示`() {
        assertEquals("更新包 2.5 MB · 覆盖安装，关注列表不会丢", packageLine(2_621_440))
        assertEquals("覆盖安装，关注列表不会丢", packageLine(0))
    }
}
