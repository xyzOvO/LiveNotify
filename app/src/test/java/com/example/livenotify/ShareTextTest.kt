package com.example.livenotify

import com.example.livenotify.util.ShareText
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTextTest {

    @Test fun `干净输入原样返回`() {
        assertEquals("https://live.bilibili.com/21452505", ShareText.extract("https://live.bilibili.com/21452505"))
        assertEquals("21452505", ShareText.extract("21452505"))
        assertEquals("live.douyin.com/629458933", ShareText.extract("live.douyin.com/629458933"))
    }

    @Test fun `B站分享文案提取链接`() {
        assertEquals(
            "https://live.bilibili.com/21452505",
            ShareText.extract("【七海Nana7mi的直播间】https://live.bilibili.com/21452505 快来围观～")
        )
    }

    @Test fun `b23tv短链`() {
        assertEquals(
            "https://b23.tv/abcDEF1",
            ShareText.extract("【直播间】https://b23.tv/abcDEF1 【点击关注不迷路】")
        )
    }

    @Test fun `无协议域名`() {
        assertEquals(
            "live.douyin.com/629458933",
            ShareText.extract("复制打开抖音，看看【xx的直播间】live.douyin.com/629458933")
        )
    }

    @Test fun `中文夹住的房间号取最长数字串`() {
        assertEquals("21452505", ShareText.extract("来我直播间吧！房间号21452505"))
        assertEquals("71415", ShareText.extract("斗鱼房间71415欢迎你"))
    }

    @Test fun `抖音19位webrid不被截断`() {
        // 上限曾写成 12 位，长房间号会被截成 "770190948185"：查不到房间，还可能被 B 站规则抢走
        assertEquals("7701909481856633636", ShareText.extract("抖音房间号 7701909481856633636 快来看"))
    }

    @Test fun `全角标点截断URL`() {
        // 句号属于全角区，不能被吸进 URL
        assertEquals(
            "https://live.bilibili.com/21452505",
            ShareText.extract("链接是https://live.bilibili.com/21452505。")
        )
    }

    @Test fun `主页链接`() {
        assertEquals(
            "https://space.bilibili.com/672328094",
            ShareText.extract("【七海Nana7mi】个人主页：https://space.bilibili.com/672328094 【关注】")
        )
    }

    @Test fun `多链接取第一个`() {
        assertEquals(
            "https://live.bilibili.com/111",
            ShareText.extract("https://live.bilibili.com/111 或者 https://live.bilibili.com/222")
        )
    }

    @Test fun `纯中文无数字原样返回`() {
        assertEquals("七海", ShareText.extract("七海"))
    }

    @Test fun `抖音分享文案提取短链`() {
        // 抖音分享文案末尾还会带一串无关的口令字符（7@8.com :1pm），URL 必须在空格处截断
        assertEquals(
            "https://v.douyin.com/L7qQF2s-ZI0/",
            ShareText.extract(
                "3- #在抖音，记录美好生活#【女流66】正在直播，来和我一起支持Ta吧。" +
                    "复制下方链接，打开【抖音】，直接观看直播！ https://v.douyin.com/L7qQF2s-ZI0/ 7@8.com :1pm"
            )
        )
    }
}
