"""生成体脂秤图标：真实字体渲染 kg，输出各密度 PNG。"""
import os
from PIL import Image, ImageDraw, ImageFont

BG = (0x3E, 0x5C, 0x6B)
FG = (255, 255, 255)
# 字体按平台自动探测，也可用环境变量 ICON_FONT 指定
FONT_CANDIDATES = [
    os.environ.get("ICON_FONT", ""),
    "C:/Windows/Fonts/arialbd.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]
FONT_BOLD = next((f for f in FONT_CANDIDATES if f and os.path.exists(f)), "")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res")
PREVIEW = os.path.join(ROOT, "tools")

DENS = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def draw_ring(d, cx, cy, r, sw, gap_deg, fill=FG, ss=1):
    """画一个顶端留缺口的圆环。PIL 角度：0=3点方向，顺时针。"""
    start = 270 + gap_deg / 2.0          # 缺口中心在正上方(270°)
    end = 270 - gap_deg / 2.0 + 360
    box = [(cx - r) * ss, (cy - r) * ss, (cx + r) * ss, (cy + r) * ss]
    d.arc(box, start, end, fill=fill, width=max(1, int(round(sw * ss))))


def draw_kg(d, cx, cy, size_units, ss=1, font_path=FONT_BOLD):
    """在 (cx,cy) 处居中绘制 kg。size_units 是 108 视窗下的字号。"""
    px = max(1, int(round(size_units * ss)))
    font = ImageFont.truetype(font_path, px) if font_path else ImageFont.load_default()
    bbox = d.textbbox((0, 0), "kg", font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = cx * ss - tw / 2.0 - bbox[0]
    y = cy * ss - th / 2.0 - bbox[1]
    d.text((x, y), "kg", font=font, fill=FG)


def make_adaptive_foreground(px, path):
    """108dp 前景：透明底 + 内容收在中心安全区内"""
    ss = 4
    W = px * ss
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = px * ss / 108.0          # 108 视窗 -> 像素
    # 自适应图标只显示中间 72dp：内容直径控制在 ~42/72，四周留出正常边距
    r = 21.0 * k
    sw = 4.6 * k
    draw_ring(d, 54 * k, 54 * k, r, sw, 0, ss=1)
    draw_kg(d, 54 * k, 54.3 * k, 15.5 * k)
    img.resize((px, px), Image.LANCZOS).save(path, "PNG")


def make_legacy(size, path, round_icon=False):
    """旧版图标：带底色的整块图标"""
    ss = 4
    W = size * ss
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if round_icon:
        d.ellipse([0, 0, W - 1, W - 1], fill=BG)
        inset = 0.02
    else:
        d.rounded_rectangle([0, 0, W - 1, W - 1], radius=int(W * 0.235), fill=BG)
        inset = 0.0
    k = W / 108.0
    # 旧版图标整块平铺、无 72/108 裁切，内容可以更饱满
    r = 34.0 * k
    sw = 6.8 * k
    draw_ring(d, 54 * k, 54 * k, r, sw, 0)
    draw_kg(d, 54 * k, 54.5 * k, 25 * k)
    img.resize((size, size), Image.LANCZOS).save(path, "PNG")


if __name__ == "__main__":
    for dens, scale in DENS.items():
        d_adapt = os.path.join(OUT, "drawable-" + dens)
        d_map = os.path.join(OUT, "mipmap-" + dens)
        os.makedirs(d_adapt, exist_ok=True)
        os.makedirs(d_map, exist_ok=True)
        make_adaptive_foreground(int(round(108 * scale)), os.path.join(d_adapt, "ic_launcher_foreground.png"))
        make_legacy(int(round(48 * scale)), os.path.join(d_map, "ic_launcher.png"))
        make_legacy(int(round(48 * scale)), os.path.join(d_map, "ic_launcher_round.png"), round_icon=True)
        print("done", dens)

    # 预览：桌面尺寸 192px + 小尺寸 48px
    make_legacy(192, os.path.join(PREVIEW, "icon_preview_big.png"))
    make_legacy(48, os.path.join(PREVIEW, "icon_preview_small.png"))
    print("preview saved")
