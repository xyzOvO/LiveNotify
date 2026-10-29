# -*- coding: utf-8 -*-
"""把一张方形头像图裁成 Android 需要的多分辨率图标 + 自适应图标前景。

用法：
    python tools/gen_icon.py --src 头像.jpg [--res app/src/main/res]

--src  源图路径（必填，任意尺寸，会自动居中裁成正方形）
--res  res 目录，默认取本脚本所在项目的 app/src/main/res
"""
import argparse
import os
import sys

from PIL import Image, ImageDraw

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def parse_args():
    here = os.path.dirname(os.path.abspath(__file__))
    default_res = os.path.join(os.path.dirname(here), "app", "src", "main", "res")
    p = argparse.ArgumentParser(description="生成 Android 启动图标")
    p.add_argument("--src", required=True, help="源图路径（jpg/png）")
    p.add_argument("--res", default=default_res, help=f"res 目录（默认 {default_res}）")
    return p.parse_args()


def make_round(im, size):
    """圆形裁切（老系统 round 图标）"""
    im = im.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4, size * 4), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def make_foreground(img, size, content_ratio=0.96):
    """自适应图标前景：108dp 画布，内容居中占 content_ratio。

    头像本身是白底，与 background 同色，放大填满不会露出方形硬边。
    """
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = max(1, int(size * content_ratio))
    art = img.resize((inner, inner), Image.LANCZOS).convert("RGBA")
    # 圆形遮罩，让前景边缘融进 background 而不是硬方形
    mask = Image.new("L", (inner * 4, inner * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, inner * 4, inner * 4), fill=255)
    mask = mask.resize((inner, inner), Image.LANCZOS)
    art.putalpha(mask)
    canvas.paste(art, ((size - inner) // 2, (size - inner) // 2), art)
    return canvas


def main():
    args = parse_args()
    if not os.path.isfile(args.src):
        sys.exit(f"源图不存在: {args.src}")

    img = Image.open(args.src).convert("RGB")
    w, h = img.size
    side = min(w, h)
    img = img.crop(
        ((w - side) // 2, (h - side) // 2, (w - side) // 2 + side, (h - side) // 2 + side)
    )
    print(f"源图裁成正方形: {img.size}")
    # 背景色取图片四角（白底），用于自适应图标 background，避免边缘突兀
    print("背景色 RGB:", img.getpixel((3, 3)))

    for dpi, px in DENSITIES.items():
        folder = os.path.join(args.res, f"mipmap-{dpi}")
        os.makedirs(folder, exist_ok=True)

        # 旧版方形图标（启动器自行加圆角）
        img.resize((px, px), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"))
        # 圆形图标
        make_round(img, px).save(os.path.join(folder, "ic_launcher_round.png"))
        # 自适应图标前景：108dp * 密度倍数
        fg_px = int(px * 108 / 48)
        make_foreground(img, fg_px).save(os.path.join(folder, "ic_launcher_foreground.png"))
        print(f"{dpi}: launcher={px}px, foreground={fg_px}px")

    print("完成")


if __name__ == "__main__":
    main()
