#!/usr/bin/env python3
"""Builds the menu's fonts into src/main/resources/assets/javahost/font/.

  python3 scripts/gen-fonts.py <Inter variable .ttf> <lucide.ttf> <lucide.css>

Needs fontTools (pip install fonttools). Inter (SIL OFL 1.1) comes from
https://github.com/google/fonts/tree/main/ofl/inter, Lucide (ISC) from the npm package lucide-static (font/).
These are for the "Smooth" text style; the default "Pixel" style uses the game's own font.
Both are cut down to the characters and icons the menu uses; the icon list is ICONS below, and Icons.java
must use the same private-use code points (U+E000 + position in this list).
"""
import json
import pathlib
import re
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/javahost/font"

# Icons.java constant -> Lucide icon name. The order is the code point order: don't reorder, only append.
ICONS = [
    ("HOME", "house"), ("HOST", "wifi"), ("JOIN", "log-in"), ("MIC", "mic"), ("MIC_OFF", "mic-off"),
    ("SPEAKER", "volume-2"), ("BOLT", "zap"), ("GEAR", "sliders-horizontal"), ("COPY", "copy"), ("CHECK", "check"),
    ("CROSS", "x"), ("WARNING", "triangle-alert"), ("USER", "user"), ("USERS", "users"), ("GLOBE", "globe"),
    ("WIFI", "wifi"), ("PLAY", "play"), ("STOP", "square"), ("PACKAGE", "package"), ("BELL", "bell"),
    ("CHAT", "message-square"), ("CAMERA", "image"), ("BACK", "arrow-left"), ("SEND", "send"),
    ("SPARKLE", "sparkles"), ("SHIRT", "shirt"), ("GAUGE", "gauge"), ("CLOUD", "cloud"), ("STAR", "star"),
    ("TRASH", "trash-2"), ("EDIT", "pencil"), ("LOCK", "lock"), ("UNLOCK", "lock-open"), ("SHIELD", "shield"),
    ("UPLOAD", "upload"), ("FOLDER", "folder"), ("DOWNLOAD", "download"), ("MORE", "ellipsis"),
    ("CHEVRON_DOWN", "chevron-down"),
]

TEXT = (list(range(0x20, 0x7F)) + list(range(0xA0, 0x180)) + list(range(0x2010, 0x2028)) + list(range(0x2030, 0x203B))
        + [0x20AC, 0x2190, 0x2191, 0x2192, 0x2193, 0x2713, 0x2022, 0x2026, 0x2122])
SCALES = range(1, 7)  # GUI scales: one font definition each, drawn at exactly that many screen pixels per GUI pixel


def options():
    o = subset.Options()
    o.layout_features = []  # the game places glyphs by their advance only
    o.name_IDs = [0, 1, 2, 13, 14]  # keep the copyright and licence names
    o.notdef_outline = True
    o.hinting = False
    o.desubroutinize = True
    return o


def text_font(source, weight, name):
    font = TTFont(source)
    instancer.instantiateVariableFont(font, {"wght": weight, "opsz": 14}, inplace=True)
    s = subset.Subsetter(options())
    s.populate(unicodes=TEXT)
    s.subset(font)
    font.save(OUT / name)
    print(name, (OUT / name).stat().st_size)


def icon_font(source, css):
    points = dict(re.findall(r'\.icon-([a-z0-9-]+)::before \{ content: "\\([0-9a-f]+)"; \}', pathlib.Path(css).read_text()))
    font = TTFont(source)
    wanted = [int(points[lucide], 16) for _, lucide in ICONS]
    s = subset.Subsetter(options())
    s.populate(unicodes=wanted)
    s.subset(font)
    # Move each icon to U+E000 + its position in ICONS, so the code never depends on Lucide's numbering.
    for table in font["cmap"].tables:
        old = dict(table.cmap)
        table.cmap = {0xE000 + i: old[int(points[lucide], 16)] for i, (_, lucide) in enumerate(ICONS) if int(points[lucide], 16) in old}
    # Centre every icon in its square (Lucide's font pushes each one to the left edge).
    from fontTools.pens.boundsPen import BoundsPen
    from fontTools.pens.transformPen import TransformPen
    from fontTools.pens.ttGlyphPen import TTGlyphPen
    glyphs = font.getGlyphSet()
    em = font["head"].unitsPerEm
    for name in set(font.getBestCmap().values()):
        bounds = BoundsPen(glyphs)
        glyphs[name].draw(bounds)
        x0, y0, x1, y1 = bounds.bounds
        dx, dy = round(em / 2 - (x0 + x1) / 2), round(em / 2 - (y0 + y1) / 2)
        pen = TTGlyphPen(None)
        glyphs[name].draw(TransformPen(pen, (1, 0, 0, 1, dx, dy)))
        font["glyf"][name] = pen.glyph()
        font["hmtx"][name] = (em, round(x0 + dx))
    font.save(OUT / "lucide.ttf")
    print("lucide.ttf", (OUT / "lucide.ttf").stat().st_size)


def definitions():
    for old in OUT.glob("*.json"):
        old.unlink()
    for scale in SCALES:
        fonts = [
                 ("ui", "inter-medium.ttf", 9.0, -0.5), ("ui_bold", "inter-semibold.ttf", 9.0, -0.5),
                 ("ui_title", "inter-semibold.ttf", 11.0, 0.0), ("ui_big", "inter-semibold.ttf", 14.0, 0.0),
                 ("icons", "lucide.ttf", 12.0, 0.0), ("icons_big", "lucide.ttf", 16.0, 0.0)]
        for name, file, size, shift in fonts:
            providers = [{"type": "ttf", "file": "javahost:" + file, "size": size, "oversample": float(scale), "shift": [0.0, shift]}]
            if not name.startswith("icons"):
                providers.append({"type": "reference", "id": "minecraft:default"})  # anything the font doesn't have
            (OUT / f"{name}_{scale}.json").write_text(json.dumps({"providers": providers}, indent="\t") + "\n")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    text_font(sys.argv[1], 500, "inter-medium.ttf")
    text_font(sys.argv[1], 600, "inter-semibold.ttf")
    icon_font(sys.argv[2], sys.argv[3])
    definitions()
