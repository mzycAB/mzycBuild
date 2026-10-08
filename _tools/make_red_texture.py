"""把黄版灯光方块贴图转成红版：保持图案与明暗结构，只把色相拉到正红。

做法：逐像素转 HSV，饱和度够的（= 有颜色的那部分）把色相设成 0（正红）并保留原饱和度/明度；
饱和度接近 0 的（= 深灰底/阴影线）原样保留。这样花纹的层次完全不变，只换色系。

跑法：python make_red_texture.py
"""

import colorsys
from collections import Counter
from pathlib import Path

from PIL import Image

ASSETS = Path(__file__).resolve().parent.parent / "src/main/resources/assets/mzycbuild"
SRC = ASSETS / "textures/block/light_block.png"
DST = ASSETS / "textures/block/red_light_block.png"

# 饱和度低于它就当「无彩色」，不动（保住深灰底和阴影线）
SAT_THRESHOLD = 0.08
# 目标色相：0.0 = 正红。稍微给一点点暖（0.99 会偏玫红），这里就取正红
TARGET_HUE = 0.0


def main():
    src = Image.open(SRC).convert("RGBA")
    dst = Image.new("RGBA", src.size)
    out = []
    for r, g, b, a in src.getdata():
        h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
        if s >= SAT_THRESHOLD:
            r2, g2, b2 = colorsys.hsv_to_rgb(TARGET_HUE, s, v)
        else:
            r2, g2, b2 = r / 255.0, g / 255.0, b / 255.0
        out.append((round(r2 * 255), round(g2 * 255), round(b2 * 255), a))

    dst.putdata(out)
    dst.save(DST)

    print(f"写入 {DST.relative_to(ASSETS.parent.parent.parent)}  ({DST.stat().st_size} B)")
    print("原图主色:", Counter(src.getdata()).most_common(4))
    print("新图主色:", Counter(dst.getdata()).most_common(4))
    print("像素数一致:", len(out) == src.size[0] * src.size[1], "尺寸:", dst.size)


if __name__ == "__main__":
    main()
