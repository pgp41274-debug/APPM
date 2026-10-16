#!/usr/bin/env python3
"""Render PdfCraft's app icon (assets/app-icon/pdfcraft-1024.png, MIT OR Apache-2.0) as an
Android adaptive launcher icon.

Usage: scripts/make-icons.py <pdfcraft checkout>

The artwork is a rounded square on a transparent 1024 px canvas. The foreground layer holds it
inside the 72 dp safe zone of the 108 dp layer; the background layer is the artwork's own teal,
so any launcher mask shape shows a seamless tile.
"""
import pathlib
import sys

from PIL import Image

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LAYER_DP = 108
ART_DP = 74

root = pathlib.Path(__file__).resolve().parent.parent
res = root / "android/app/src/main/res"
src = pathlib.Path(sys.argv[1]) / "assets/app-icon/pdfcraft-1024.png"

art = Image.open(src).convert("RGBA")
art = art.crop(art.getbbox())
# The tile colour, a few pixels inside the artwork's left edge, halfway down.
r, g, b, _ = art.getpixel((6, art.height // 2))
background = f"#{r:02X}{g:02X}{b:02X}"

for name, scale in DENSITIES.items():
    size = round(LAYER_DP * scale)
    inner = round(ART_DP * scale)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    scaled = art.resize((inner, inner), Image.LANCZOS)
    offset = (size - inner) // 2
    layer.alpha_composite(scaled, (offset, offset))
    out = res / f"mipmap-{name}"
    out.mkdir(parents=True, exist_ok=True)
    layer.save(out / "ic_launcher_foreground.png", optimize=True)

# The shell page's loading screen.
art.resize((192, 192), Image.LANCZOS).save(
    root / "android/app/src/main/assets/app/shell/icon.png", optimize=True
)

(res / "values/ic_launcher_background.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    f'    <color name="ic_launcher_background">{background}</color>\n</resources>\n'
)
(res / "mipmap-anydpi").mkdir(parents=True, exist_ok=True)
(res / "mipmap-anydpi/ic_launcher.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/ic_launcher_background" />\n'
    '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
    '</adaptive-icon>\n'
)
print(f"icons written; background {background}")
