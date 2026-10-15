"""重新生成工程根目录的两张人看的预览图（不随 jar 分发，纯核对用）：

    item-icon-preview.png   ← textures/item/*.png    16x 放大
    texture-preview.png     ← textures/block/*.png   16x 放大

两张图**版式完全一致**，所以「方块贴图 == 物品栏图标」这件事可以直接用眼睛比：
把两张图并排看，应该一模一样。第三方建筑模组（轻松建造 / Effortless Building 之类）
的放置预览渲染的是**方块模型** → 读 textures/block/*.png，也就是 texture-preview.png 这张。

脚本末尾用 ImageChops 逐像素比对两张输出图，不同就报出来。

跑法：python make_previews.py
"""

from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/mzycbuild"

ZOOM = 16
PANEL = 16 * ZOOM                 # 256
PAD = 25                          # 左 / 右 / 下留白
GAP = 25                          # 两个面板之间
HEADER = 45                       # 顶部标题区高度
BACKDROP = (58, 58, 62, 255)      # 深色外框底
CANVAS = (198, 198, 198, 255)     # 面板底（浅灰，透明像素看得清）
TEXT = (229, 229, 230, 255)

NAMES = ("light_block.png", "red_light_block.png")


def make(kind: str) -> Path:
    w = PAD * 2 + PANEL * len(NAMES) + GAP * (len(NAMES) - 1)
    h = HEADER + PANEL + PAD
    im = Image.new("RGBA", (w, h), BACKDROP)
    d = ImageDraw.Draw(im)
    for i, name in enumerate(NAMES):
        x = PAD + i * (PANEL + GAP)
        y = HEADER
        d.rectangle([x - 1, y - 1, x + PANEL, y + PANEL], fill=CANVAS)
        src = Image.open(ASSETS / f"textures/{kind}/{name}").convert("RGBA")
        assert src.size == (16, 16), f"{name} 不是 16x16: {src.size}"
        im.alpha_composite(src.resize((PANEL, PANEL), Image.NEAREST), (x, y))
        d.text((x, 20), f"textures/{kind}/{name}  16x16 ({ZOOM}x zoom)", fill=TEXT)
    out = ROOT / ("item-icon-preview.png" if kind == "item" else "texture-preview.png")
    im.save(out)
    print(f"写入 {out.name}  {im.size}  <- textures/{kind}/*.png")
    return out


def main():
    a = Image.open(make("item")).convert("RGBA")
    b = Image.open(make("block")).convert("RGBA")
    bbox = ImageChops.difference(a, b).getbbox()
    if bbox is None:
        print("两张预览图逐像素一致: 通过（方块贴图与物品栏图标确实是同一张画）")
    else:
        print(f"两张预览图不一致，差异范围: {bbox}")


if __name__ == "__main__":
    main()
