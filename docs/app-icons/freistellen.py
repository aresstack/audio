"""Stellt einen App-Icon-Entwurf frei (schwarze Ecken werden transparent) und schreibt die Größen,
die das Studio als Fenster- und Taskleisten-Icon lädt.

Aufruf: python3 freistellen.py entwurf-as.png ../../studio/src/main/resources/com/aresstack/audio/studio/icons/as
"""
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageChops
src, outdir = sys.argv[1], sys.argv[2]
im = Image.open(src).convert("RGB")
w, h = im.size
r, g, b = im.split()
mx = ImageChops.lighter(ImageChops.lighter(r, g), b)
mask = mx.point(lambda v: 255 if v > 28 else 0)
for p in [(0,0),(w-1,0),(0,h-1),(w-1,h-1)]:
    if mask.getpixel(p) == 0:
        ImageDraw.floodfill(mask, p, 128)
alpha = mask.point(lambda v: 0 if v == 128 else 255).filter(ImageFilter.GaussianBlur(1.2))
rgba = im.copy(); rgba.putalpha(alpha)
bbox = alpha.point(lambda v: 255 if v > 8 else 0).getbbox()
rgba = rgba.crop(bbox)
s = max(rgba.size); m = round(s * 0.02)
sq = Image.new("RGBA", (s + 2*m, s + 2*m), (0,0,0,0))
sq.paste(rgba, ((s - rgba.size[0])//2 + m, (s - rgba.size[1])//2 + m))

for n in [16,20,24,32,40,48,64,128,256]:
    sq.resize((n,n), Image.LANCZOS).save(f"{outdir}/{n}.png", optimize=True)
