"""Inspect actual Java quantizer output; optional Pillow-based visual QA."""
from pathlib import Path
import sys
from PIL import Image, ImageDraw

directory = Path(sys.argv[1])
target = Path(sys.argv[2])
palette = {(0, 0, 0), (255, 255, 255), (245, 205, 0), (200, 0, 0)}
names = ["source", "color-plain", "color-dither", "binary-plain", "binary-dither"]
sheet = Image.new("RGB", (5 * 288, 418), "#eeeeee")
draw = ImageDraw.Draw(sheet)
for index, name in enumerate(names):
    with Image.open(directory / f"raster-{name}.ppm") as image:
        image = image.convert("RGB")
        if name != "source":
            allowed = {(0, 0, 0), (255, 255, 255)} if name.startswith("binary") else palette
            assert set(image.getdata()) <= allowed, name
        sheet.paste(image.resize((276, 384), Image.Resampling.NEAREST), (index * 288 + 6, 28))
        draw.text((index * 288 + 6, 7), name, fill="black")
target.parent.mkdir(parents=True, exist_ok=True)
sheet.save(target)
print("PASS independent output palette check:", target)
