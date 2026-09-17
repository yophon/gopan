#!/usr/bin/env python3
"""生成 gopan 的 PWA 图标。

零依赖:stdlib 的 zlib + struct 手写 PNG(仓库惯例是能不加依赖就不加)。
图形 = 品牌蓝圆角方底 + 白色文件夹剪影;maskable 版整面铺满、内容缩到中央
80% 安全区。PNG 必须提交进仓库 —— .dockerignore 排掉了 scripts/,镜像构建
阶段跑不了这个脚本。

用法:python scripts/gen_pwa_icons.py(在仓库根目录执行,写到 web/public/icons/)
"""

import struct
import zlib
from pathlib import Path

SS = 3  # 超采样倍数,抗锯齿

BG = (64, 158, 255)  # 品牌蓝(Element Plus primary,与 theme-color 一致)
FG = (255, 255, 255)  # 白色剪影

OUT = Path(__file__).resolve().parent.parent / "web" / "public" / "icons"


# ---------- PNG 编码 ----------

def _chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def write_png(path: Path, size: int, rows: list[bytes]) -> None:
    raw = b"".join(b"\x00" + row for row in rows)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
        + _chunk(b"IDAT", zlib.compress(raw, 9))
        + _chunk(b"IEND", b"")
    )


# ---------- 形状 ----------

def in_rounded_rect(x: float, y: float, size: float, radius: float) -> bool:
    """左上角原点、边长 size 的圆角矩形。"""
    if x < 0 or y < 0 or x > size or y > size:
        return False
    # 只需要针对"最近的那个角"做圆弧判定:对四个角都判会把远角的距离也算进来
    nx = min(x, size - x)
    ny = min(y, size - y)
    if nx >= radius or ny >= radius:
        return True
    return (nx - radius) ** 2 + (ny - radius) ** 2 <= radius**2


def in_folder(x: float, y: float, size: float) -> bool:
    """居中的文件夹剪影:上檐小盖 + 主体大盒,直角(小尺寸下圆角反而糊)。"""
    cx, cy = size / 2, size / 2
    w = size * 0.62
    h = size * 0.42
    left, right = cx - w / 2, cx + w / 2
    top, bottom = cy - h / 2 + size * 0.02, cy + h / 2 + size * 0.04
    tab_w, tab_h = w * 0.42, size * 0.075
    # 上檐:靠左的一小块
    if left <= x <= left + tab_w and top - tab_h <= y <= top:
        return True
    # 主体
    return left <= x <= right and top <= y <= bottom


# ---------- 渲染 ----------

def render(size: int, maskable: bool) -> list[bytes]:
    """返回 size×size 的 RGBA 行;内部按 SS 倍超采样抗锯齿。"""
    big = size * SS
    radius = 0 if maskable else big * 0.22
    # maskable 的安全区:内容缩到中央 80%
    content = (big / size) * (0.8 * size if maskable else size)
    rows: list[bytes] = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            r = g = b = a = 0
            for sy in range(SS):
                for sx in range(SS):
                    x = px * SS + sx + 0.5
                    y = py * SS + sy + 0.5
                    # 把大画布坐标映射到"以 size 为基准"的内容坐标系
                    off = (big - content) / 2
                    cx, cy = x - off, y - off
                    if not maskable and not in_rounded_rect(x, y, big, radius):
                        continue  # 圆角外全透明,不累加
                    if 0 <= cx <= content and 0 <= cy <= content and in_folder(cx, cy, content):
                        sr, sg, sb = FG
                    else:
                        sr, sg, sb = BG
                    r += sr
                    g += sg
                    b += sb
                    a += 255
            n = SS * SS
            row += bytes((round(r / n), round(g / n), round(b / n), round(a / n)))
        rows.append(bytes(row))
    return rows


def main() -> None:
    targets = [
        ("icon-192.png", 192, False),
        ("icon-512.png", 512, False),
        ("icon-512-maskable.png", 512, True),
        ("apple-touch-icon-180.png", 180, False),
    ]
    for name, size, maskable in targets:
        out = OUT / name
        write_png(out, size, render(size, maskable))
        print(f"{out} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
