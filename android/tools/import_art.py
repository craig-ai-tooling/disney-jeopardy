"""Imports the final branding into the app. Run from the repo root or anywhere: python3 android/tools/import_art.py

- android-art/icon-1024.png        -> legacy + round launcher icons and adaptive icon foreground (all densities)
- android-art/banner-*.png         -> Leanback banner (xhdpi 320x180, plus xxhdpi/xxxhdpi from the 1280x720 art)
- public/theme/*                   -> app/src/main/assets/theme/ (bundled theme pack), manifest.json files list
"""
import json
import os
import shutil

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
ART = os.path.join(REPO, "android-art")
THEME_SRC = os.path.join(REPO, "public", "theme")
MAIN = os.path.join(REPO, "android", "app", "src", "main")
RES = os.path.join(MAIN, "res")
THEME_DST = os.path.join(MAIN, "assets", "theme")
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
# The source icon is a rounded tile with a gold rim; this crop keeps the art inside the rim for masked icons.
INTERIOR = 0.075


def save(img, *path):
    out = os.path.join(RES, *path)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out, optimize=True)


def interior(icon):
    s = icon.width
    m = round(s * INTERIOR)
    return icon.crop((m, m, s - m, s - m)).convert("RGBA")


def circle(img):
    mask = Image.new("L", (img.width * 4, img.height * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, mask.width - 1, mask.height - 1), fill=255)
    out = img.copy()
    out.putalpha(mask.resize(img.size, Image.LANCZOS))
    return out


def icons():
    icon = Image.open(os.path.join(ART, "icon-1024.png")).convert("RGBA")
    inner = interior(icon)
    for d, k in DENSITIES.items():
        px = round(48 * k)
        save(icon.resize((px, px), Image.LANCZOS), f"mipmap-{d}", "ic_launcher.png")
        save(circle(inner.resize((px, px), Image.LANCZOS)), f"mipmap-{d}", "ic_launcher_round.png")
        # Adaptive foreground: 108dp canvas; the launcher shows the middle 72dp, so the art fills that 72dp (same crop as the round icon).
        canvas, art = round(108 * k), round(72 * k)
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        off = (canvas - art) // 2
        fg.paste(inner.resize((art, art), Image.LANCZOS), (off, off))
        save(fg, f"mipmap-{d}", "ic_launcher_foreground.png")


def banners():
    small = Image.open(os.path.join(ART, "banner-320x180.png")).convert("RGB")
    big = Image.open(os.path.join(ART, "banner-1280x720.png")).convert("RGB")
    save(small.resize((320, 180), Image.LANCZOS), "drawable-xhdpi", "banner.png")
    save(big.resize((480, 270), Image.LANCZOS), "drawable-xxhdpi", "banner.png")
    save(big.resize((640, 360), Image.LANCZOS), "drawable-xxxhdpi", "banner.png")


def theme():
    os.makedirs(THEME_DST, exist_ok=True)
    keep = {"README.md", "manifest.json"}
    for name in os.listdir(THEME_DST):
        if name not in keep:
            p = os.path.join(THEME_DST, name)
            shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
    files = []
    for root, _, names in os.walk(THEME_SRC):
        for n in names:
            rel = os.path.relpath(os.path.join(root, n), THEME_SRC)
            dst = os.path.join(THEME_DST, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(os.path.join(root, n), dst)
            files.append(rel.replace(os.sep, "/"))
    mpath = os.path.join(THEME_DST, "manifest.json")
    version = 1
    if os.path.exists(mpath):
        version = json.load(open(mpath)).get("version", 1)
    with open(mpath, "w") as f:
        json.dump({"version": version, "files": sorted(files)}, f, indent=1)
        f.write("\n")
    print(f"theme pack v{version}: {len(files)} files")


if __name__ == "__main__":
    icons()
    banners()
    theme()
