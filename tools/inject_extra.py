#!/usr/bin/env python3
"""把环境变量 EXTRA_LINES 里的条目写进 EasterEggs.kt 的占位符。

条目本身不进公开仓库，仓库里只留 `// @@ENTRIES@@` 一行占位。
CI 在构建前从 Secrets 取出条目注入，构建产物因此与本地构建一致。
未配置 EXTRA_LINES 时保持原样（空列表，功能静默关闭）。
"""

import os
import pathlib
import sys

TARGET = pathlib.Path("app/src/main/java/com/example/livenotify/data/EasterEggs.kt")
MARKER = "// @@ENTRIES@@"


def main() -> int:
    lines = os.environ.get("EXTRA_LINES", "").strip()
    if not lines:
        print("EXTRA_LINES 未配置，跳过注入")
        return 0

    src = TARGET.read_text(encoding="utf-8")
    if MARKER not in src:
        print("占位符不存在，跳过注入", file=sys.stderr)
        return 0

    TARGET.write_text(src.replace(MARKER, lines), encoding="utf-8")
    print("已注入 %d 个字符" % len(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
