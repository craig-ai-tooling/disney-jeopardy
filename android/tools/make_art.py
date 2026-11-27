"""Renders the launcher icon and Leanback banner (original castle + sparkle art). Run: python3 tools/make_art.py"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
GOLD = (255, 212, 90)
NAVY = (10, 15, 60)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"


def sky(w, h):
    img = Image.new("RGB", (w, h))
    top, bottom = (8, 10, 48), (72, 32, 128)
    for y in range(h):
        t = y / (h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        ImageDraw.Draw(img).line([(0, y), (w, y)], fill=c)
    return img.convert("RGBA")


def star(draw, cx, cy, r, color):
    pts = []
    for i in range(8):
        ang = math.pi / 4 * i - math.pi / 2
        rad = r if i % 2 == 0 else r * 0.28
        pts.append((cx + rad * math.cos(ang), cy + rad * math.sin(ang)))
    draw.polygon(pts, fill=color)


def sparkles(img, n, seed, max_r, avoid=None):
    rnd = random.Random(seed)
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    w, h = img.size
    for _ in range(n):
        x, y = rnd.uniform(0, w), rnd.uniform(0, h * 0.75)
        r = rnd.uniform(max_r * 0.15, max_r)
        if avoid and avoid[0] < x < avoid[2] and avoid[1] < y < avoid[3]:
            r *= 0.3
        star(d, x, y, r, (255, 244, 200, rnd.randint(150, 255)))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(max_r * 0.12)))
    img.alpha_composite(glow)


def castle(draw, cx, base, s, color):
    """Original stylised castle silhouette centred on cx, standing on y=base, scale s."""
    def tower(x, w, h, roof):
        draw.rectangle([x - w / 2, base - h, x + w / 2, base], fill=color)
        draw.polygon([(x - w / 2 - w * 0.12, base - h), (x + w / 2 + w * 0.12, base - h), (x, base - h - roof)], fill=color)
        draw.line([(x, base - h - roof), (x, base - h - roof - s * 0.12)], fill=color, width=max(1, int(s * 0.025)))
        draw.polygon([(x, base - h - roof - s * 0.12), (x + s * 0.1, base - h - roof - s * 0.09), (x, base - h - roof - s * 0.06)], fill=color)

    draw.rectangle([cx - s * 1.1, base - s * 0.55, cx + s * 1.1, base], fill=color)
    for i in range(-5, 6):
        x = cx + i * s * 0.2
        draw.rectangle([x - s * 0.06, base - s * 0.63, x + s * 0.06, base - s * 0.55], fill=color)
    tower(cx - s * 1.0, s * 0.26, s * 0.85, s * 0.42)
    tower(cx + s * 1.0, s * 0.26, s * 0.85, s * 0.42)
    tower(cx - s * 0.55, s * 0.3, s * 1.15, s * 0.5)
    tower(cx + s * 0.55, s * 0.3, s * 1.15, s * 0.5)
    tower(cx, s * 0.46, s * 1.55, s * 0.75)
    door_w = s * 0.24
    draw.rectangle([cx - door_w / 2, base - s * 0.32, cx + door_w / 2, base], fill=(255, 220, 140, 255))
    draw.ellipse([cx - door_w / 2, base - s * 0.44, cx + door_w / 2, base - s * 0.2], fill=(255, 220, 140, 255))
    for x in (cx - s * 0.55, cx + s * 0.55, cx):
        draw.rectangle([x - s * 0.04, base - s * 0.95, x + s * 0.04, base - s * 0.82], fill=(255, 220, 140, 255))


def castle_layer(size, cx, base, s):
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    castle(ImageDraw.Draw(layer), cx, base, s, (16, 18, 70, 255))
    glow = Image.new("RGBA", size, (0, 0, 0, 0))
    castle(ImageDraw.Draw(glow), cx, base, s, (*GOLD, 200))
    glow = glow.filter(ImageFilter.GaussianBlur(s * 0.06))
    out = Image.new("RGBA", size, (0, 0, 0, 0))
    out.alpha_composite(glow)
    out.alpha_composite(layer)
    return out


def text(img, xy, s, size, anchor="lm"):
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, size)
    d.text((xy[0] + size * 0.05, xy[1] + size * 0.07), s, font=font, fill=(0, 0, 0, 180), anchor=anchor)
    d.text(xy, s, font=font, fill=GOLD, anchor=anchor, stroke_width=max(1, size // 30), stroke_fill=(120, 70, 10))


def banner(w=640, h=360):
    img = sky(w, h)
    sparkles(img, 55, 7, h * 0.05, avoid=(w * 0.4, h * 0.2, w, h * 0.8))
    img.alpha_composite(castle_layer(img.size, w * 0.22, h * 0.93, h * 0.29))
    ImageDraw.Draw(img).rectangle([0, h * 0.93, w, h], fill=(16, 18, 70, 255))
    text(img, (w * 0.715, h * 0.4), "DISNEY", int(h * 0.15), anchor="mm")
    text(img, (w * 0.715, h * 0.62), "JEOPARDY", int(h * 0.15), anchor="mm")
    star(ImageDraw.Draw(img), w * 0.93, h * 0.17, h * 0.06, (255, 250, 220, 255))
    return img.convert("RGB")


def icon(px):
    big = 512
    img = sky(big, big)
    sparkles(img, 30, 3, big * 0.05)
    img.alpha_composite(castle_layer(img.size, big * 0.5, big * 0.84, big * 0.3))
    ImageDraw.Draw(img).rectangle([0, big * 0.84, big, big], fill=(16, 18, 70, 255))
    text(img, (big * 0.5, big * 0.13), "DJ", int(big * 0.17), anchor="mm")
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=big * 0.18, fill=255)
    out = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out.resize((px, px), Image.LANCZOS)


if __name__ == "__main__":
    os.makedirs(f"{RES}/drawable-xhdpi", exist_ok=True)
    banner().save(f"{RES}/drawable-xhdpi/banner.png", optimize=True)
    for name, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        os.makedirs(f"{RES}/mipmap-{name}", exist_ok=True)
        icon(px).save(f"{RES}/mipmap-{name}/ic_launcher.png", optimize=True)
