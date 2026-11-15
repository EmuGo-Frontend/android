#!/usr/bin/env python3
"""Generate the EmuGo! launcher icons from the bundled Nunito Bold glyph.

Requires fonttools and Pillow when regenerating; neither is needed to build the app.
"""

from pathlib import Path
from xml.sax.saxutils import escape

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from PIL import Image, ImageChops, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / "app/src/main/assets/fonts/nunito-bold.ttf"
RES = ROOT / "app/src/main/res"
ICON_SIZE = 108
GLYPH_TOP = 24
GLYPH_BOTTOM = 84
STEM_BANDS = [(24, 30), (31.2, 37.2), (38.4, 44.4),
              (45.6, 51.6), (52.8, 58.8), (60, 66.6)]
DOT_BAND = (70, 86)


def glyph_path():
    font = TTFont(FONT)
    glyphs = font.getGlyphSet()
    glyph = glyphs[font.getBestCmap()[ord("!")]]
    bounds = BoundsPen(glyphs)
    glyph.draw(bounds)
    x0, y0, x1, y1 = bounds.bounds
    scale = (GLYPH_BOTTOM - GLYPH_TOP) / (y1 - y0)
    x_offset = ICON_SIZE / 2 - scale * (x0 + x1) / 2
    y_offset = GLYPH_TOP + scale * y1
    pen = SVGPathPen(glyphs, ntos=lambda value: f"{value:.3f}".rstrip("0").rstrip("."))
    glyph.draw(TransformPen(pen, (scale, 0, 0, -scale, x_offset, y_offset)))
    return pen.getCommands()


def vector_icon(path):
    bands = STEM_BANDS + [DOT_BAND]
    clip = " ".join(f"M0,{top}H108V{bottom}H0Z" for top, bottom in bands)
    return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group>
        <clip-path android:pathData="{clip}"/>
        <path android:fillColor="#FFFFFF" android:pathData="{escape(path)}"/>
    </group>
</vector>
'''


def render_logo():
    size = 512
    font = ImageFont.truetype(str(FONT), 1000)
    sample = Image.new("L", (1000, 1200))
    draw = ImageDraw.Draw(sample)
    bounds = draw.textbbox((0, 0), "!", font=font)
    draw.text((-bounds[0], -bounds[1]), "!", font=font, fill=255)
    glyph = sample.crop((0, 0, bounds[2] - bounds[0], bounds[3] - bounds[1]))
    height = round(size * (GLYPH_BOTTOM - GLYPH_TOP) / ICON_SIZE)
    width = round(height * glyph.width / glyph.height)
    glyph = glyph.resize((width, height), Image.Resampling.LANCZOS)

    alpha = Image.new("L", (size, size))
    alpha.paste(glyph, ((size - width) // 2, round(size * GLYPH_TOP / ICON_SIZE)))
    bands = Image.new("L", (size, size))
    band_draw = ImageDraw.Draw(bands)
    for top, bottom in STEM_BANDS + [DOT_BAND]:
        band_draw.rectangle((0, round(size * top / ICON_SIZE),
                             size - 1, round(size * bottom / ICON_SIZE)), fill=255)
    alpha = ImageChops.multiply(alpha, bands)
    logo = Image.new("RGBA", (size, size), "black")
    logo.paste(Image.new("RGBA", (size, size), "white"), (0, 0), alpha)
    logo.save(ROOT / "logo.png")


def main():
    mark = RES / "drawable/ic_emugo_mark.xml"
    mark.write_text(vector_icon(glyph_path()))
    adaptive = RES / "mipmap-anydpi-v26/ic_launcher.xml"
    adaptive.parent.mkdir(parents=True, exist_ok=True)
    adaptive.write_text('''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/black"/>
    <foreground android:drawable="@drawable/ic_emugo_mark"/>
    <monochrome android:drawable="@drawable/ic_emugo_mark"/>
</adaptive-icon>
''')
    render_logo()


if __name__ == "__main__":
    main()
