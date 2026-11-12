"""记事本品牌图标生成脚本。

设计：琥珀渐变圆角底 + 白色纸页（右上折角）+ 石墨色文字线。
输出：
  public/icon-512.png / icon-192.png / icon-maskable-512.png
  android mipmap-*/ic_launcher(.round|_foreground).png
  android drawable*/splash.png
用法：python scripts/make-icon.py
"""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
PUBLIC = ROOT / "public"
RES = ROOT / "android-app" / "android" / "app" / "src" / "main" / "res"

S = 4  # 超采样倍数：先按 1024 * S 画，再缩放

AMBER_TOP = (240, 180, 92)
AMBER_BOTTOM = (204, 138, 44)
PAGE = (255, 255, 255)
FOLD = (214, 214, 222)
INK = (38, 38, 42)
SHADOW = (60, 36, 10)


def _gradient(size: int) -> Image.Image:
    """左上偏亮、右下偏深的线性渐变。"""
    img = Image.new("RGB", (size, size), AMBER_BOTTOM)
    d = ImageDraw.Draw(img)
    for i in range(size):
        t = i / max(1, size - 1)
        # 轻微的非线性，让亮部集中在左上
        e = t ** 0.92
        col = tuple(
            int(AMBER_TOP[c] + (AMBER_BOTTOM[c] - AMBER_TOP[c]) * e) for c in range(3)
        )
        d.line([(0, i), (size, i)], fill=col)
    overlay = Image.new("L", (size, size), 0)
    od = ImageDraw.Draw(overlay)
    od.ellipse(
        [-size * 0.35, -size * 0.45, size * 0.95, size * 0.7],
        fill=44,
    )
    overlay = overlay.filter(ImageFilter.GaussianBlur(size * 0.12))
    light = Image.new("RGB", (size, size), (255, 244, 224))
    img = Image.composite(light, img, overlay)
    return img


def _page_layer(size: int, scale: float = 1.0) -> Image.Image:
    """白色纸页 + 折角 + 文字线，透明底。几何按 1024 画布定义，再按实际尺寸映射。"""
    n = size
    layer = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    k = (n / 1024.0) * scale
    cx = cy = n / 2.0

    def mx(x):
        return cx + (x - 512) * k

    def my(y):
        return cy + (y - 512) * k

    # 纸页几何（1024 画布下的绝对坐标）
    px0, py0, px1, py1 = 286, 196, 738, 828
    r = 36 * k
    cut = 104 * k

    x0, y0, x1, y1 = mx(px0), my(py0), mx(px1), my(py1)
    fx, fy = x1 - cut, y0 + cut

    mask = Image.new("L", (n, n), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([x0, y0, x1, y1], radius=r, fill=255)
    # 右上角切掉一个三角，形成折角
    md.polygon([(fx, y0 - 2), (x1 + 2, y0 - 2), (x1 + 2, fy)], fill=0)

    page = Image.new("RGBA", (n, n), PAGE + (255,))
    layer.paste(page, (0, 0), mask)

    # 折角面：切角内侧的浅灰小块
    d.polygon(
        [(fx, y0 + 1), (x1, fy), (fx, fy)],
        fill=FOLD + (255,),
    )

    # 文字线：四条，最后一条短
    line_h = (y1 - y0) * 0.048
    left = x0 + (x1 - x0) * 0.14
    right_full = x1 - (x1 - x0) * 0.14
    right_short = x1 - (x1 - x0) * 0.52
    for i in range(4):
        ly = y0 + (y1 - y0) * (0.30 + i * 0.155)
        lx1 = right_short if i == 3 else right_full
        d.rounded_rectangle(
            [left, ly, lx1, ly + line_h], radius=line_h / 2, fill=INK + (255,)
        )

    return layer


def icon(size: int, rounded: bool = True, content_scale: float = 1.0) -> Image.Image:
    n = size
    bg = _gradient(n).convert("RGBA")

    if rounded:
        mask = Image.new("L", (n, n), 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            [0, 0, n - 1, n - 1], radius=int(n * 0.228), fill=255
        )
        bg.putalpha(mask)

    page = _page_layer(n, scale=content_scale)

    # 纸页投影
    shadow = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    smask = page.split()[3].filter(ImageFilter.GaussianBlur(n * 0.022))
    shadow.paste(SHADOW + (110,), (int(n * 0.012), int(n * 0.016)), smask)

    out = Image.alpha_composite(bg, shadow)
    out = Image.alpha_composite(out, page)
    return out


def glyph(size: int, content_scale: float = 0.95) -> Image.Image:
    """透明底的前景层，用于安卓自适应图标。"""
    n = size
    page = _page_layer(n, scale=content_scale)
    shadow = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    smask = page.split()[3].filter(ImageFilter.GaussianBlur(n * 0.01))
    shadow.paste(SHADOW + (90,), (int(n * 0.006), int(n * 0.008)), smask)
    return Image.alpha_composite(shadow, page)


def splash(w: int, h: int) -> Image.Image:
    """深底 + 居中图标，和 App 的深色界面一致。"""
    base = Image.new("RGB", (w, h), (22, 22, 26))
    side = int(min(w, h) * 0.30)
    mark = icon(side).resize((side, side), Image.LANCZOS)
    base.paste(mark, ((w - side) // 2, (h - side) // 2), mark)
    return base


def save_icon(target: Path, px: int, rounded: bool = True, content_scale: float = 1.0):
    img = icon(px * S, rounded=rounded, content_scale=content_scale)
    img = img.resize((px, px), Image.LANCZOS)
    target.parent.mkdir(parents=True, exist_ok=True)
    img.save(target, "PNG", optimize=True)
    print(f"{target.relative_to(ROOT)}  {px}x{px}")


def save_glyph(target: Path, px: int):
    img = glyph(px * S).resize((px, px), Image.LANCZOS)
    target.parent.mkdir(parents=True, exist_ok=True)
    img.save(target, "PNG", optimize=True)
    print(f"{target.relative_to(ROOT)}  {px}x{px}")


def main():
    # 网页
    save_icon(PUBLIC / "icon-512.png", 512)
    save_icon(PUBLIC / "icon-192.png", 192)
    save_icon(PUBLIC / "icon-maskable-512.png", 512, rounded=False, content_scale=0.78)

    # 安卓旧版启动图标（48/72/96/144/192）
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, px in densities.items():
        save_icon(RES / f"mipmap-{name}" / "ic_launcher.png", px)
        # 圆形版本
        img = icon(px * S).resize((px, px), Image.LANCZOS)
        round_mask = Image.new("L", (px, px), 0)
        ImageDraw.Draw(round_mask).ellipse([0, 0, px - 1, px - 1], fill=255)
        img.putalpha(round_mask)
        img.save(RES / f"mipmap-{name}" / "ic_launcher_round.png", "PNG", optimize=True)
        print(f"mipmap-{name}/ic_launcher_round.png  {px}x{px}")
        save_glyph(RES / f"mipmap-{name}" / "ic_launcher_foreground.png", int(px * 2.25))

    # 安卓启动页：按现有每张图的实际尺寸重绘
    for p in sorted(RES.glob("drawable*/splash.png")):
        old = Image.open(p)
        w, h = old.size
        splash(w, h).save(p, "PNG", optimize=True)
        print(f"{p.relative_to(ROOT)}  {w}x{h}")


if __name__ == "__main__":
    main()
