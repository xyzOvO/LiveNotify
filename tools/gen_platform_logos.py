# -*- coding: utf-8 -*-
"""把抓取到的各平台官方 favicon 处理成圆形徽标，输出各密度 drawable。

用法：
    python tools/gen_platform_logos.py --src <存放源图的目录> [--out app/src/main/res]

源图目录里需要这几个文件（可用 yandex favicon 服务获取，约 120px）：
    bili_yd.png  douyin_yd.png  douyu_yd.png  huya_yd.png
"""
import argparse
import os
import sys

from PIL import Image, ImageDraw

DENSITIES = {"mdpi": 20, "hdpi": 30, "xhdpi": 40, "xxhdpi": 60, "xxxhdpi": 80}
FILES = {
    "bilibili": "bili_yd.png",
    "douyin": "douyin_yd.png",
    "douyu": "douyu_yd.png",
    "huya": "huya_yd.png",
}


def parse_args():
    here = os.path.dirname(os.path.abspath(__file__))
    default_out = os.path.join(os.path.dirname(here), "app", "src", "main", "res")
    p = argparse.ArgumentParser(description="生成平台徽标 drawable")
    p.add_argument("--src", required=True, help="存放源图的目录")
    p.add_argument("--out", default=default_out, help=f"res 目录（默认 {default_out}）")
    return p.parse_args()


def patch_bilibili(im):
    """B站 favicon 左上角带站点水印，用底色三角盖掉（在圆形裁切前处理）"""
    w, h = im.size
    fill = im.getpixel((w // 2, 6))          # 采样顶部中间的粉色
    d = ImageDraw.Draw(im)
    d.polygon([(0, 0), (int(w * 0.52), 0), (0, int(h * 0.52))], fill=fill)
    return im


def round_badge(im, size, inset=0.94):
    """先按 inset 内缩再圆形遮罩，避免文字/图形被边缘切掉"""
    inner = int(size * inset)
    art = im.resize((inner, inner), Image.LANCZOS)
    mask = Image.new("L", (inner * 4, inner * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, inner * 4, inner * 4), fill=255)
    mask = mask.resize((inner, inner), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(art, ((size - inner) // 2, (size - inner) // 2), mask)
    return canvas


def main():
    args = parse_args()
    if not os.path.isdir(args.src):
        sys.exit(f"源图目录不存在: {args.src}")

    previews = []
    for name, fn in FILES.items():
        path = os.path.join(args.src, fn)
        if not os.path.isfile(path):
            print(f"[跳过] {name}: 缺少 {path}")
            continue
        im = Image.open(path).convert("RGBA")
        print(f"{name}: {im.size}")
        if name == "bilibili":
            im = patch_bilibili(im)
        for dpi, px in DENSITIES.items():
            folder = os.path.join(args.out, f"drawable-{dpi}")
            os.makedirs(folder, exist_ok=True)
            round_badge(im, px).save(os.path.join(folder, f"ic_platform_{name}.png"))
        previews.append(round_badge(im, 96))

    if previews:
        canvas = Image.new("RGB", (len(previews) * 116 + 20, 120), (245, 245, 248))
        for i, p in enumerate(previews):
            canvas.paste(p, (20 + i * 116, 12), p)
        preview_path = os.path.join(args.src, "final_logos.png")
        canvas.save(preview_path)
        print("预览图:", preview_path)
    print("完成")


if __name__ == "__main__":
    main()
