"""从 web/public/icons/icon-512.png 生成安卓启动图标(legacy + adaptive 前景)。

用法:python app/gen_icons.py(需要 Pillow)。PWA 图标是唯一的图标源,安卓这边只做尺寸派生,
不另外维护一套设计。adaptive icon 的前景按 108dp 规格把图标缩到中央 66% 的安全区。
"""
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "..", "web", "public", "icons", "icon-512.png")
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

# dpi → legacy 图标边长 / adaptive 前景边长
SIZES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def main() -> None:
    if not os.path.exists(SRC):
        sys.exit(f"源图标不存在:{SRC}")
    src = Image.open(SRC).convert("RGBA")
    for dpi, (legacy, fg) in SIZES.items():
        d = os.path.join(RES, f"mipmap-{dpi}")
        os.makedirs(d, exist_ok=True)
        icon = src.resize((legacy, legacy), Image.LANCZOS)
        icon.save(os.path.join(d, "ic_launcher.png"))
        icon.save(os.path.join(d, "ic_launcher_round.png"))
        # adaptive 前景:透明画布,图标占中央 66%
        canvas = Image.new("RGBA", (fg, fg), (0, 0, 0, 0))
        inner = int(fg * 0.66)
        small = src.resize((inner, inner), Image.LANCZOS)
        off = (fg - inner) // 2
        canvas.paste(small, (off, off), small)
        canvas.save(os.path.join(d, "ic_launcher_foreground.png"))
        print(f"{dpi}: {legacy}px legacy, {fg}px foreground")


if __name__ == "__main__":
    main()
