"""生成黄 / 红灯光方块的**物品栏图标**：等距线框立方体，线宽 1px。

背景透明、16x16（原版物品图标的标准尺寸，1px 就是它的原生单位）。
画法与游戏内「放出来的样子」一致：把整方块线框按**真等距**视角投影
（水平半宽 A ≈ √3·垂直步长 B，立方体才不会被压扁）——
正六边形外轮廓 + 从近处顶点（投影后落在正中心）出发的三条棱（左上 / 右上 / 正下），
一共 9 条**可见**棱，每条 1px（被挡住的远竖直棱不画，这样才读得出是个立方体）。

只改物品栏图标；方块贴图（textures/block/*.png）与游戏内渲染（BER 线框）都不动。

跑法：python make_item_icons.py
"""

from pathlib import Path

from PIL import Image, ImageDraw

ASSETS = Path(__file__).resolve().parent.parent / "src/main/resources/assets/mzycbuild"
OUT_DIR = ASSETS / "textures/item"

SIZE = 16
CX, CY = 8, 8
A = 5   # 水平半宽
B = 3   # 每走一格 (x+z) 的垂直步长（A/B ≈ √3 ⇒ 真等距，不压扁）

T = (CX, CY - 2 * B)          # 顶
UR = (CX + A, CY - B)         # 右上
LR = (CX + A, CY + B)         # 右下
BO = (CX, CY + 2 * B)         # 底
LL = (CX - A, CY + B)         # 左下
UL = (CX - A, CY - B)         # 左上
C = (CX, CY)                  # 中心（近处顶点投影后落在这里）

# 9 条可见棱 = 正六边形外轮廓 6 条 + 中心出发的 3 条（左上 / 右上 / 正下）
EDGES = [
    (T, UR), (UR, LR), (LR, BO), (BO, LL), (LL, UL), (UL, T),   # 外轮廓
    (C, UL), (C, UR), (C, BO),                                   # 三条可见棱
]

COLORS = {
    "light_block.png": (255, 214, 0, 255),       # 亮黄，同游戏内线框
    "red_light_block.png": (255, 38, 31, 255),   # 亮红，同游戏内线框
}


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, color in COLORS.items():
        im = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        for a, b in EDGES:
            d.line([a, b], fill=color, width=1)   # width=1 ⇒ 正好 1px
        # 顶点补实心像素，避免斜线端点缺角
        for p in {T, UR, LR, BO, LL, UL, C}:
            im.putpixel(p, color)
        out = OUT_DIR / name
        im.save(out)
        print(f"写入 textures/item/{name}  ({out.stat().st_size} B)  {im.size}")

    print("可见棱数:", len(EDGES), "（六边形 6 + 三条可见棱 3）")


if __name__ == "__main__":
    main()
