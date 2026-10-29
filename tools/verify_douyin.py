#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抖音链路体检脚本（与 Kotlin 版 DouyinParser 逻辑一致）。

用途：
  1) 验证 webcast 移动端落地页还能不能拿到直播间数据；
  2) 跑一组离线样例，防止解析器回归（空壳页、访客 nickname 干扰项等）。

用法: python tools/verify_douyin.py                    # 离线自检
      python tools/verify_douyin.py 7701909481856633636 [...]

说明：抖音官方未开放个人可用的直播间状态接口，App 读的是
      https://webcast.amemv.com/douyin/webcast/reflow/<房间号> 这个移动端落地页，
      它免登录、免风控签名，且只认移动端 UA（PC UA 会 404）。
"""
import json
import re
import subprocess
import sys

# 落地页只认移动端 UA，PC UA 会直接 404
UA = ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
      "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1")
REFLOW = "https://webcast.amemv.com/douyin/webcast/reflow/%s"

STATUS_LIVE = 2


def fetch(url: str) -> str:
    cmd = ["curl", "-sS", "--compressed", "--max-time", "20", "-A", UA, "-L", url]
    try:
        return subprocess.run(cmd, capture_output=True, timeout=30).stdout.decode(
            "utf-8", "replace"
        )
    except Exception as e:  # noqa: BLE001
        print("  请求失败: %s" % e)
        return ""


def unescape(s: str) -> str:
    return s.replace('\\"', '"').replace("\\\\", "\\").replace("\\u0026", "&")


def find_str(src: str, key: str):
    m = re.search(r'"%s"\s*:\s*"((?:[^"\\]|\\.)*)"' % key, src)
    return m.group(1) if m else None


def find_long(src: str, key: str):
    m = re.search(r'"%s"\s*:\s*(-?\d+)' % key, src)
    return int(m.group(1)) if m else None


def owner_nickname(text: str) -> str:
    """页面里有多处 nickname，访客对象的值是 $undefined，要跳过"""
    start = 0
    while True:
        i = text.find('"owner":{', start)
        if i < 0:
            return ""
        start = i + 1
        nick = find_str(text[i:i + 4000], "nickname")
        if nick and nick != "$undefined":
            return nick


def parse(html: str, fallback_rid: str):
    """返回 dict；ok=True 表示拿到了可用结论"""
    if not html.strip():
        return {"ok": False, "error": "空响应"}
    text = unescape(html)

    anchor = min(
        [i for i in (text.find('"room":{"id":'), text.find('"room":{"idStr":')) if i >= 0],
        default=-1,
    )
    if anchor < 0:
        if len(html) < 40000:
            # 空壳页：房间号无效或当前没有数据，App 侧按「未开播」处理
            return {"ok": True, "status": 4, "nickname": "", "title": "", "shell": True,
                    "web_rid": fallback_rid, "create_time": 0}
        return {"ok": False, "error": "未找到房间数据（页面结构可能已变更）"}

    win = text[anchor:anchor + 6000]
    status = find_long(win, "status")
    if status is None:
        return {"ok": False, "error": "找到 room 但没有 status（结构可能已变更）"}

    return {
        "ok": True,
        "status": status,
        "live": status == STATUS_LIVE,
        "web_rid": find_str(win, "idStr") or fallback_rid,
        "nickname": owner_nickname(text),
        "title": find_str(win, "title") or "",
        "create_time": find_long(win, "createTime") or 0,
        "shell": False,
    }


# ── 离线自检 ────────────────────────────────────────────────────────────
def page(status: int) -> str:
    return (
        '<html><body><script>push([1,"{\\"data\\":{'
        '\\"room\\":{\\"id\\":7701909481856634000,\\"idStr\\":\\"7701909481856633636\\",'
        '\\"status\\":%d,\\"ownerUserId\\":61608272917,\\"title\\":\\"测试标题\\",'
        '\\"userCount\\":58589,\\"createTime\\":1793240555},'
        '\\"user\\":{\\"id\\":0,\\"nickname\\":\\"$undefined\\",\\"status\\":0},'
        '\\"owner\\":{\\"id\\":61608272917,\\"nickname\\":\\"测试主播\\"}'
        '}}"])</script></body></html>' % status
    )


def self_test() -> int:
    cases = [
        ("直播中", page(2),
         lambda r: r["ok"] and r["status"] == 2 and r["nickname"] == "测试主播"),
        ("未开播", page(4),
         lambda r: r["ok"] and r["status"] == 4 and r["nickname"] == "测试主播"),
        ("空壳页按未开播处理", '<html><title>抖音</title></html>',
         lambda r: r["ok"] and r["status"] == 4),
        ("空响应报错", "", lambda r: not r["ok"]),
    ]
    failed = 0
    for name, html, check in cases:
        r = parse(html, "629458933")
        ok = check(r)
        print("  %-22s %s" % (name, "PASS" if ok else "FAIL -> %s" % json.dumps(r, ensure_ascii=False)))
        if not ok:
            failed += 1
    print("  离线自检 %d/%d 通过" % (len(cases) - failed, len(cases)))
    return failed


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print("离线自检：")
        return 1 if self_test() else 0

    bad = 0
    for rid in args:
        print("[%s]" % rid)
        html = fetch(REFLOW % rid)
        if not html:
            print("  拉取失败（网络或短链失效）")
            bad += 1
            continue
        r = parse(html, rid)
        if not r["ok"]:
            print("  FAIL %s" % r["error"])
            bad += 1
            continue
        if r.get("shell"):
            print("  空壳页：该房间号当前没有数据（按未开播处理），页面 %d 字节" % len(html))
            continue
        print("  %s | %s | %s | createTime=%s | web_rid=%s | 页面 %d 字节"
              % ("直播中" if r["live"] else "未开播", r["nickname"], r["title"],
                 r["create_time"], r["web_rid"], len(html)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
