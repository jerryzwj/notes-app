"""Generate 简·笔记本 launcher / PWA icons: flat green rounded square + white outlined page."""
import os
from PIL import Image, ImageDraw

ROOT = r"D:\code\notes-app"
RES = os.path.join(ROOT, "android-compose", "app", "src", "main", "res")
PUB = os.path.join(ROOT, "public")

GREEN = (44, 186, 112, 255)   # #2CBA70 sampled from the reference icon
WHITE = (255, 255, 255, 255)
SS = 4                        # supersample factor

# normalized geometry (tile = 1.0) — page with folded top-right corner, rounded corners elsewhere
import math

CR = 0.052  # corner radius


def arc(cx, cy, r, a0, a1, steps=8):
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / steps)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / steps))) for i in range(steps + 1)]


X0, Y0, X1, Y1 = 0.265, 0.215, 0.735, 0.785
OUTLINE = (
    arc(X0 + CR, Y0 + CR, CR, 180, 270)          # top-left
    + [(0.635, Y0)]                              # top edge
    + [(X1, 0.315)]                              # chamfer (folded corner)
    + [(X1, Y1 - CR)]
    + arc(X1 - CR, Y1 - CR, CR, 0, 90)           # bottom-right
    + [(X0 + CR, Y1)]
    + arc(X0 + CR, Y1 - CR, CR, 90, 180)         # bottom-left
    + [(X0, Y0 + CR)]
)
FOLD = []
STROKE = 0.045
LINES = [
    ((0.355, 0.415), (0.645, 0.415)),
    ((0.355, 0.515), (0.645, 0.515)),
    ((0.355, 0.615), (0.545, 0.615)),
]


def draw_art(size, art_scale=1.0, offset=(0.5, 0.5)):
    """Return RGBA layer with the white line-art page, art_scale = fraction of the tile the art spans."""
    canvas = size * SS
    layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    def T(pt):
        x, y = pt
        return ((x - 0.5) * art_scale + offset[0]) * canvas, ((y - 0.5) * art_scale + offset[1]) * canvas

    w = int(round(STROKE * art_scale * canvas))
    rr = w / 2.0

    def stroke_path(points):
        pts = [T(p) for p in points]
        d.line(pts, fill=WHITE, width=w, joint="curve")
        for p in pts:
            d.ellipse([p[0] - rr, p[1] - rr, p[0] + rr, p[1] + rr], fill=WHITE)

    stroke_path(OUTLINE + [OUTLINE[0]])
    for a, b in LINES:
        stroke_path([a, b])

    return layer.resize((size, size), Image.LANCZOS)


def square_icon(size, radius_ratio=0.225):
    bg = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(bg)
    r = int(size * SS * radius_ratio)
    d.rounded_rectangle([0, 0, size * SS - 1, size * SS - 1], radius=r, fill=GREEN)
    bg = bg.resize((size, size), Image.LANCZOS)
    art = draw_art(size)
    return Image.alpha_composite(bg, art)


def circle_icon(size):
    bg = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(bg)
    d.ellipse([0, 0, size * SS - 1, size * SS - 1], fill=GREEN)
    bg = bg.resize((size, size), Image.LANCZOS)
    art = draw_art(size)
    return Image.alpha_composite(bg, art)


def solid_icon(size):
    bg = Image.new("RGBA", (size, size), GREEN)
    return Image.alpha_composite(bg, draw_art(size))


def foreground(size):
    return draw_art(size)


def save(img, path):
    img.save(path, "PNG", optimize=True)
    print("wrote", os.path.basename(os.path.dirname(path)) + "/" + os.path.basename(path), img.size)


if __name__ == "__main__":
    # web / PWA
    save(square_icon(512), os.path.join(PUB, "icon-512.png"))
    save(square_icon(192), os.path.join(PUB, "icon-192.png"))
    save(solid_icon(512), os.path.join(PUB, "icon-maskable-512.png"))

    # android legacy + round + adaptive foreground
    dens = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
    for name, scale in dens.items():
        launcher = int(48 * scale)
        save(square_icon(launcher), os.path.join(RES, f"mipmap-{name}", "ic_launcher.png"))
        save(circle_icon(launcher), os.path.join(RES, f"mipmap-{name}", "ic_launcher_round.png"))
        fg = int(108 * scale)
        save(foreground(fg), os.path.join(RES, f"mipmap-{name}", "ic_launcher_foreground.png"))
