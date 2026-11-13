#!/usr/bin/env python3
"""Draws the page's original pixel art (block textures, sky, stars, clouds, sun, moon, hills) and writes it into
index.html between the gen: markers. Standard library only. Run: python3 tools/gen.py"""
import base64, math, os, re, struct, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
PAGE = os.environ.get("PAGE") or os.path.join(HERE, "..", "..", "website", "index.html")


def rgba(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def png(rows):
    h, w = len(rows), len(rows[0])
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in rows)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def uri(rows):
    return 'url("data:image/png;base64,' + base64.b64encode(png(rows)).decode() + '")'


def art(text, pal):
    rows = [r for r in text.strip().split("\n")]
    w = len(rows[0])
    for r in rows:
        assert len(r) == w, (r, len(r), w)
    return [[rgba(pal[c]) if c in pal else (0, 0, 0, 0) for c in r] for r in rows]


GRASS = {"g": "#45cf7c", "l": "#78eaa2", "d": "#2fb267"}
DIRT = {"b": "#8f5d45", "B": "#a87255", "D": "#744734", "p": "#c39472"}
STONE = {"s": "#7d889d", "L": "#9aa6ba", "k": "#616c81"}
DEEP = {"s": "#586277", "L": "#6d778e", "k": "#454e62"}
WOOD = {"w": "#7c5437", "W": "#986a43", "d": "#5a3a26"}
PETAL = {"p": "#f58ab5", "l": "#ffc3da", "d": "#e0629a", "h": "#bd4680"}
PLANK = {"a": "#dcab69", "A": "#f1c98b", "e": "#b3834a"}
ROOF = {"o": "#f59e3b", "O": "#ffc473", "r": "#cc7a20"}
WATER = {"u": "#4f9df7", "U": "#a6d2ff", "v": "#3884e0"}
SAND = {"y": "#f1de9d", "Y": "#fff3c4", "z": "#d9c27b"}

T = {}
T["grass"] = art("""
gggggglggggg
gdgggggggdgg
ggglgggggggg
ggggggdggglg
gggggggggggg
lggdgggglggg
gggggggggggd
gglggggdgggg
gggggggggggg
gdgggglggglg
ggggdggggggg
gggggggggdgg
""", GRASS)
T["grass-side"] = art("""
gggggglggggg
gdgggggggdgg
ggglgggggggg
ggbgggbbgggb
bbbbgbbbbgbb
bbBbbbbDbbbb
bbbbbbbbbbpb
bDbbbBbbbbbb
bbbbbbbbbDbb
bbbpbbbbbbbb
bbbbbbDbbbBb
Bbbbbbbbbbbb
""", {**GRASS, **DIRT})
T["dirt"] = art("""
bbbbbbDbbbbb
bbBbbbbbbbpb
bbbbbbbbDbbb
bDbbbBbbbbbb
bbbbbbbbbbbB
bbbpbbbDbbbb
bbbbbbbbbbbb
Bbbbbbbbbpbb
bbbDbbBbbbbb
bbbbbbbbbbDb
bpbbbbbbbbbb
bbbbbDbbBbbb
""", DIRT)
STONE_ART = """
ssssssLLssss
sskkssssssss
ssssssssskks
Lsssskssssss
sssssksssLLs
ssssssssssss
skkssssLssss
ssssssssssks
ssLLsssssssk
ssssssskksss
ssssssssssss
ksssLLsssssk
"""
T["stone"] = art(STONE_ART, STONE)
T["deep"] = art(STONE_ART, DEEP)
ORE_ART = """
ssssssLLssss
sskkssssssss
ssXxsssssssk
ssxxskssssss
sssssksssLLs
ssssssssssss
skkssssXxsss
sssssssxxsks
ssLLsssssssk
sssssXxsksss
sssssxxsssss
ksssssssLssk
"""
T["ore-g"] = art(ORE_ART, {**STONE, "X": "#b9ffd6", "x": "#3ddc84"})
T["ore-b"] = art(ORE_ART, {**DEEP, "X": "#cfe6ff", "x": "#4f9df7"})
T["ore-p"] = art(ORE_ART, {**DEEP, "X": "#e6d9ff", "x": "#a77bf3"})
T["log"] = art("""
wdwwWwwdwwWw
wdwwWwwdwwWw
wdwwWwwdwwww
wdwwwwwdwwWw
wwwwWwwdwwWw
wdwwWwwdwwWw
wdwwWwwwwwWw
wdwwWwwdwwWw
wdwwwwwdwwWw
wdwwWwwdwwww
wwwwWwwdwwWw
wdwwWwwdwwWw
""", WOOD)
T["leaf"] = art("""
pplppppdpppp
plllpppppdpp
pplppphppppl
ppppppppppll
dpppplpppppl
ppphpllppdpp
ppppplpppppp
pdppppppphpp
ppppdpplpppp
lpppppplllpp
llpphpppldpp
lpppppdppppp
""", PETAL)
T["plank"] = art("""
AAAAAAAAAAAA
aaaaaaaaeaaa
aaaaaaaaeaaa
eeeeeeeeeeee
AAAAAAAAAAAA
aaaeaaaaaaaa
aaaeaaaaaaaa
eeeeeeeeeeee
AAAAAAAAAAAA
aaaaaaaaaeaa
aaaaaaaaaeaa
eeeeeeeeeeee
""", PLANK)
T["roof"] = art("""
OOOOOOOOOOOO
oooooroooooo
oooooroooooo
rrrrrrrrrrrr
OOOOOOOOOOOO
oorooooooroo
oorooooooroo
rrrrrrrrrrrr
OOOOOOOOOOOO
oooooooorooo
oooooooorooo
rrrrrrrrrrrr
""", ROOF)
T["water"] = art("""
uuuuuuuuuuuu
uUUUuuuuuuuu
uuuuuuuvvuuu
uuuuuuuuuuuu
uuuuuUUUUuuu
vvuuuuuuuuuu
uuuuuuuuuuuU
uuuvvuuuuuuu
uuuuuuuuUUuu
uUUuuuuuuuuu
uuuuuuvvuuuu
uuuuuuuuuuuu
""", WATER)
T["fall"] = art("""
uUuuvuuUuuuv
uUuuvuuUuuuv
uUuuuuuUuuuv
uuuuuuuUuuuu
uuuuvuuuuuuu
uUuuvuuuuuuv
uUuuvuuUuuuv
uUuuuuuUuuuv
uUuuuuuUuuuu
uuuuvuuUuuuu
uuuuvuuuuuuv
uUuuvuuUuuuv
""", WATER)
T["sand"] = art("""
yyyyyyYyyyyy
yzyyyyyyyyzy
yyyyyyyyyyyy
yyyYyyyzyyyy
yyyyyyyyyyyY
zyyyyyyyyyyy
yyyyyYyyyzyy
yyzyyyyyyyyy
yyyyyyyyYyyy
Yyyyyzyyyyyy
yyyyyyyyyyzy
yyyYyyyyyyyy
""", SAND)


def lcg(seed):
    s = seed
    while True:
        s = (s * 1103515245 + 12345) & 0x7FFFFFFF
        yield (s >> 8) / float(1 << 23)


def sky(colours, bands, w=4):
    """A banded sky: flat bands with six rows of ordered dither between each pair."""
    rows = []
    for i, c in enumerate(colours):
        a = rgba(c)
        rows += [[a] * w for _ in range(bands[i])]
        if i + 1 < len(colours):
            b = rgba(colours[i + 1])
            for pat in ("baaa", "aaba", "baba", "abab", "babb", "bbba"):
                rows.append([a if ch == "a" else b for ch in pat])
    return rows


SKY = sky(["#1d63de", "#2b76ec", "#3f8cf6", "#5aa3fb", "#7dbcff", "#a5d3ff"], [10, 12, 12, 12, 12, 14])


def stars(seed, n, size, plus=1):
    """A sparse night sky: single pixels at a few brightnesses, and now and then a plus-shaped bright one."""
    rows = [[(0, 0, 0, 0)] * size for _ in range(size)]
    g = lcg(seed)
    tints = ["#ffffff", "#cfe0ff", "#ffffff", "#ffe9c2", "#ffffff", "#e6d9ff"]
    for i in range(n):
        x, y = 2 + int(next(g) * (size - 4)), 2 + int(next(g) * (size - 4))
        rows[y][x] = rgba(tints[i % len(tints)], (255, 150, 215, 110)[i % 4])
        if i < plus:
            rows[y][x] = rgba("#ffffff")
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                rows[y + dy][x + dx] = rgba("#ffffff", 120)
    return rows


css = ["      /* gen:textures (drawn by tools/gen.py; original pixel art, 12x12 each) */", "      :root {"]
for name, rows in T.items():
    css.append("        --t-%s: %s;" % (name, uri(rows)))
css.append("        --sky-day: %s;" % uri(SKY))
css.append("        --sky-day-h: %dpx;" % (len(SKY) * 8))
css.append("        --stars-a: %s;" % uri(stars(7, 9, 96)))
css.append("        --stars-b: %s;" % uri(stars(41, 8, 128)))
css.append("      }")
css.append("      /* /gen:textures */")


# ---------------------------------------------------------------- vector pixel art, as <symbol>s (they take currentColor)
def runs(text, groups):
    """ASCII art to rects: one per horizontal run. groups maps a character to a fill."""
    rows = text.strip("\n").split("\n")
    out = []
    for y, row in enumerate(rows):
        x = 0
        while x < len(row):
            ch = row[x]
            if ch in groups:
                x2 = x
                while x2 < len(row) and row[x2] == ch:
                    x2 += 1
                out.append('<rect x="%d" y="%d" width="%d" height="1" fill="%s"/>' % (x, y, x2 - x, groups[ch]))
                x = x2
            else:
                x += 1
    return "".join(out), max(len(r) for r in rows), len(rows)


def symbol(name, text, groups):
    body, w, h = runs(text, groups)
    return '<symbol id="%s" viewBox="0 0 %d %d">%s</symbol>' % (name, w, h, body)


def disc(r, fill_of):
    """A pixel disc of radius r as ASCII, each cell's character chosen by fill_of(distance, x, y)."""
    rows = []
    for y in range(-r, r + 1):
        row = ""
        for x in range(-r, r + 1):
            d = math.hypot(x, y)
            row += fill_of(d, x, y)
        rows.append(row)
    return "\n".join(rows)


CLOUD_A = """
......####..........
....########..###...
..#################.
.###################
####################
.sssssssssssssssss..
"""
CLOUD_B = """
....###.......
..#######.##..
.############.
##############
.ssssssssssss.
"""
CLOUD_C = """
.........#####............
......#########..####.....
...###################....
.########################.
##########################
..ssssssssssssssssssssss..
"""
SUN = disc(9, lambda d, x, y: "c" if d <= 5.3 else "o" if d <= 7.3 else "h" if d <= 9.3 else ".")
MOON = disc(8, lambda d, x, y: "." if d > 8.3 else "s" if math.hypot(x - 4, y + 3) <= 7.4 and False else "k" if (x, y) in [(-3, -2), (-2, -2), (-3, -1), (2, 3), (3, 3), (2, 4), (-1, 4), (4, -3)] else "m")
ISLE = """
....##............
...####...........
...####...........
....##............
.tttttttttttttttt.
##################
.###############..
..#############...
...##########.....
....########......
.....#####........
......###.........
......##..........
"""
FIRE_A = """
.....y......
....yy......
....yoy.....
...yooy.....
...yoooy....
..yooroy....
..yorroy....
..yorrroy...
...orrro....
.LLdLLdLLd..
LLddLLddLLd.
"""
FIRE_B = """
......y.....
.....yy.....
....yoy.....
....yooy....
...yoooy....
...yoroy....
..yorrooy...
..yorrroy...
...orrro....
.LLdLLdLLd..
LLddLLddLLd.
"""
FIRE = {"y": "#ffe45c", "o": "#ff9f2e", "r": "#ff5a2a", "L": "#8f5d45", "d": "#5a3a26"}


def steps(name, heights, unit=1):
    """A stepped skyline as one path: heights are in units, the shape is filled down to the bottom."""
    top = max(heights)
    d = "M0 %d" % (top - heights[0])
    for i, h in enumerate(heights):
        if i and h != heights[i - 1]:
            d += "H%dV%d" % (i, top - h)
    d += "H%dV%dH0Z" % (len(heights), top + 6)
    DIMS[name] = (len(heights), top + 6)
    return '<symbol id="%s" viewBox="0 0 %d %d" preserveAspectRatio="none"><path fill="currentColor" d="%s"/></symbol>' % (name, len(heights), top + 6, d)


DIMS = {}


def bank(n, bumps):
    """Cloud tops: the upper edge of a row of pixel discs."""
    hs = []
    for x in range(n):
        h = 1
        for cx, r in bumps:
            for off in (-n, 0, n):
                dx = x - (cx + off)
                if abs(dx) <= r:
                    h = max(h, int(round(math.sqrt(r * r - dx * dx) * 0.72)) + 1)
        hs.append(h)
    return hs


def make_bank(n, seed, lo, hi):
    g = lcg(seed)
    bumps, x = [], 0
    while x < n:
        r = int(lo + next(g) * (hi - lo))
        bumps.append((x, r))
        x += int(r * (0.9 + next(g) * 0.8))
    return bank(n, bumps)


def make_hills(n, seed, amp, base):
    g = lcg(seed)
    p1, p2, p3 = next(g) * 6.28, next(g) * 6.28, next(g) * 6.28
    hs = []
    for x in range(n):
        t = x / n * 2 * math.pi
        v = math.sin(t * 2 + p1) * 0.5 + math.sin(t * 5 + p2) * 0.3 + math.sin(t * 11 + p3) * 0.2
        hs.append(max(1, int(round(base + (v * 0.5 + 0.5) * amp))))
    # hold each height for at least two columns so the steps read as pixels
    return [hs[i - (i % 2)] for i in range(n)]


svg = ["      <!-- gen:symbols (drawn by tools/gen.py) -->"]
svg.append("      " + symbol("px-cloud-a", CLOUD_A, {"#": "currentColor", "s": "var(--cloud-shade, #cfe6fb)"}))
svg.append("      " + symbol("px-cloud-b", CLOUD_B, {"#": "currentColor", "s": "var(--cloud-shade, #cfe6fb)"}))
svg.append("      " + symbol("px-cloud-c", CLOUD_C, {"#": "currentColor", "s": "var(--cloud-shade, #cfe6fb)"}))
svg.append("      " + symbol("px-sun", SUN, {"c": "var(--sun-core, #fffbe0)", "o": "var(--sun, #ffe066)", "h": "var(--sun-halo, rgba(255,240,170,.45))"}))
svg.append("      " + symbol("px-moon", MOON, {"m": "#eef2ff", "k": "#c3cbe8"}))
svg.append("      " + symbol("px-fire-a", FIRE_A, FIRE))
svg.append("      " + symbol("px-fire-b", FIRE_B, FIRE))
svg.append("      " + steps("px-bank-a", make_bank(300, 5, 7, 20)))
svg.append("      " + steps("px-bank-b", make_bank(300, 23, 9, 26)))
svg.append("      " + steps("px-hills-a", make_hills(240, 3, 12, 3)))
svg.append("      " + steps("px-hills-b", make_hills(240, 11, 9, 2)))
svg.append("      <!-- /gen:symbols -->")

if os.path.exists(PAGE):
    page = open(PAGE, encoding="utf-8").read()
    page, a = re.subn(r"      /\* gen:textures.*?/\* /gen:textures \*/", lambda m: "\n".join(css), page, flags=re.S)
    page, b = re.subn(r"      <!-- gen:symbols.*?<!-- /gen:symbols -->", lambda m: "\n".join(svg), page, flags=re.S)
    open(PAGE, "w", encoding="utf-8").write(page)
    print(DIMS)
    print("patched: textures %d, symbols %d; css %d bytes, svg %d bytes" % (a, b, len("\n".join(css)), len("\n".join(svg))))
