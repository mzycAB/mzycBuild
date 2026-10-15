"""生成黄 / 红灯光方块的**贴图**：等距线框立方体，线宽 1px。
一次写齐 **4 个文件**（方块贴图 + 物品栏图标，画法/颜色完全同源）：

    textures/block/light_block.png       textures/block/red_light_block.png
    textures/item/light_block.png        textures/item/red_light_block.png

背景透明、16x16（原版贴图的标准尺寸，1px 就是它的原生单位）。
画法与游戏内「放出来的样子」一致：把整方块线框按**真等距**视角投影
（水平半宽 A ≈ √3·垂直步长 B，立方体才不会被压扁）——
正六边形外轮廓 + 从近处顶点（投影后落在正中心）出发的三条棱（左上 / 右上 / 正下），
一共 9 条**可见**棱，每条 1px（被挡住的远竖直棱不画，这样才读得出是个立方体）。

★ 为什么 block 贴图也要一起写（2026-10-15 起的修复）：
放方块前的**预览**（轻松建造 / Effortless Building 这类建筑模组）渲染的是**方块模型**
（`models/block/*.json` → `textures/block/*.png`），而不是物品模型。
本模组方块本体是 `BlockRenderType.INVISIBLE`（游戏内由 BER 画线框，压根不读方块贴图），
所以第三方预览里唯一露出来的就是 `textures/block/*.png`。
旧版那两张 block 贴图是**把原版 `structure_block.png` 按色相改成黄 / 红**
（16x16 结构方块那个圆环图案），于是预览里看着像结构方块。
现在两边同一套画法、同一组颜色，并在下面断言**字节完全一致**，
预览与物品栏自然长得一样，也不会再各写各的漂移。

游戏内渲染（BER 线框）不受影响：方块是 INVISIBLE，这 4 张 png 只有
物品栏（item）与第三方放置预览（block）会读到。

跑法：python make_item_icons.py
"""

from pathlib import Path

from PIL import Image, ImageDraw

ASSETS = Path(__file__).resolve().parent.parent / "src/main/resources/assets/mzycbuild"

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

# 同名（不含目录）的黄 / 红共用一套颜色；block 与 item 目录都写这两张
COLORS = {
    "light_block.png": (255, 214, 0, 255),       # 亮黄，同游戏内线框
    "red_light_block.png": (255, 38, 31, 255),   # 亮红，同游戏内线框
}
OUT_DIRS = ("textures/block", "textures/item")


def render(color):
    im = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for a, b in EDGES:
        d.line([a, b], fill=color, width=1)      # width=1 ⇒ 正好 1px
    # 顶点补实心像素，避免斜线端点缺角
    for p in {T, UR, LR, BO, LL, UL, C}:
        im.putpixel(p, color)
    return im


def main():
    written = {}
    for rel in OUT_DIRS:
        out_dir = ASSETS / rel
        out_dir.mkdir(parents=True, exist_ok=True)
        for name, color in COLORS.items():
            out = out_dir / name
            render(color).save(out)
            written[rel + "/" + name] = out
            print(f"写入 {rel}/{name}  ({out.stat().st_size} B)  {SIZE}x{SIZE}")

    # 硬校验：block 与 item 必须逐字节一致，否则「预览 = 物品栏的样子」当场不成立
    for name in COLORS:
        b = written["textures/block/" + name].read_bytes()
        i = written["textures/item/" + name].read_bytes()
        assert b == i, f"block 与 item 不一致: {name} ({len(b)}B vs {len(i)}B)"
    print("block == item 逐字节一致: 通过")

    # 硬校验：确实是「透明底 + 只有两种颜色」的线框画法（防止有人悄悄塞回结构方块贴图）
    for name, color in COLORS.items():
        im = written["textures/block/" + name]
        px = Image.open(im).convert("RGBA").load()
        cols = {px[x, y] for y in range(SIZE) for x in range(SIZE)}
        assert cols == {(0, 0, 0, 0), color}, f"{name} 颜色集合异常: {sorted(cols)}"
    print("线框画法校验（透明底 + 单色线）: 通过")

    print("可见棱数:", len(EDGES), "（六边形 6 + 三条可见棱 3）")


if __name__ == "__main__":
    main()
