#!/usr/bin/env python3
"""Paints the player skins the website's 3D characters wear, into website/media/.

  python3 scripts/gen-website-skins.py

They are original pixel art in the standard 64x64 two-layer player-skin layout (not Mojang's textures):
- skin-host-<accent>.png: a hoodie in each of the mod's five accent colours, dark hair, and a headset with a mic.
- skin-friend.png: a pale hoodie, blond hair under a beanie.
The second layer (hair volume, hood, cuffs, chunky shoes, headset) is drawn slightly outside the body by the page,
which is what gives the model its depth.
"""
import pathlib
import struct
import zlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "website/media"
ACCENTS = {"green": (61, 220, 132), "blue": (79, 157, 247), "purple": (167, 123, 243), "orange": (245, 158, 59), "pink": (240, 107, 170)}

# Where each face of each part is in the layout: (base, overlay), faces in the order top, bottom, right, front, left, back.
PARTS = {
    "head": ((8, 8, 8), [(8, 0), (16, 0), (0, 8), (8, 8), (16, 8), (24, 8)], [(40, 0), (48, 0), (32, 8), (40, 8), (48, 8), (56, 8)]),
    "body": ((8, 12, 4), [(20, 16), (28, 16), (16, 20), (20, 20), (28, 20), (32, 20)], [(20, 32), (28, 32), (16, 36), (20, 36), (28, 36), (32, 36)]),
    "armR": ((4, 12, 4), [(44, 16), (48, 16), (40, 20), (44, 20), (48, 20), (52, 20)], [(44, 32), (48, 32), (40, 36), (44, 36), (48, 36), (52, 36)]),
    "armL": ((4, 12, 4), [(36, 48), (40, 48), (32, 52), (36, 52), (40, 52), (44, 52)], [(52, 48), (56, 48), (48, 52), (52, 52), (56, 52), (60, 52)]),
    "legR": ((4, 12, 4), [(4, 16), (8, 16), (0, 20), (4, 20), (8, 20), (12, 20)], [(4, 32), (8, 32), (0, 36), (4, 36), (8, 36), (12, 36)]),
    "legL": ((4, 12, 4), [(20, 48), (24, 48), (16, 52), (20, 52), (24, 52), (28, 52)], [(4, 48), (8, 48), (0, 52), (4, 52), (8, 52), (12, 52)]),
}
FACES = ["top", "bottom", "right", "front", "left", "back"]


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def shade(c, by):
    return tuple(max(0, min(255, round(v * by))) for v in c)


class Skin:
    def __init__(self):
        self.px = [[(0, 0, 0, 0)] * 64 for _ in range(64)]

    def paint(self, part, layer, fn):
        """fn(face, x, y, w, h) -> colour or None, for every pixel of every face of a part's layer (0 base, 1 overlay).
        On side faces x runs toward... right face: x = w-1 is the front edge; left face: x = 0 is the front edge."""
        (w, h, d), base, over = PARTS[part]
        for face, (x0, y0) in zip(FACES, over if layer else base):
            fw = w if face in ("top", "bottom", "front", "back") else d
            fh = d if face in ("top", "bottom") else h
            for y in range(fh):
                for x in range(fw):
                    c = fn(face, x, y, fw, fh)
                    if c is not None:
                        # A little grain and a soft fall of light down each face, the same every run.
                        grain = 1 + (((x0 + x) * 7 + (y0 + y) * 13 + (x0 + x) * (y0 + y)) % 5 - 2) * 0.014
                        light = 1.05 - 0.11 * (y / max(1, fh - 1)) if face not in ("top", "bottom") else 1.0
                        self.px[y0 + y][x0 + x] = shade(c, grain * light) + (255,)

    def save(self, name):
        rows = bytearray()
        for row in self.px:
            rows.append(0)
            for pixel in row:
                rows += bytes(pixel)

        def chunk(kind, data):
            body = kind + data
            return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

        png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 6, 0, 0, 0))
               + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))
        (OUT / name).write_bytes(png)
        return len(png)


def character(hoodie, hair, tone, trousers, headwear, trim):
    s = Skin()
    hair2, hair3 = shade(hair, 0.82), shade(hair, 1.18)
    tone2, tone3 = shade(tone, 0.86), shade(tone, 0.72)
    hood, hood2, hood3 = hoodie, shade(hoodie, 0.8), shade(hoodie, 0.62)
    jeans2 = shade(trousers, 0.8)
    shoe, sole, ink, white = (26, 30, 38), (226, 231, 238), (14, 20, 26), (246, 248, 250)
    iris = (52, 84, 128)
    lip = mix(tone, (170, 70, 70), 0.45)
    blush = mix(tone, (235, 120, 120), 0.3)

    # ---------------------------------------------------------------- head
    def head(face, x, y, w, h):
        if face == "top":
            return hair3 if (x + y) % 5 == 0 else hair
        if face == "bottom":
            return tone3
        if face == "back":
            return hair2 if y > 5 and x in (0, 7) else hair if y < 7 else tone2
        if face in ("right", "left"):
            front = w - 1 - x if face == "right" else x  # 0 at the front edge
            if y < 3 or (y < 5 and front > 1) or (y < 6 and front > 4):
                return hair
            if y == 4 and front == 3:
                return tone3  # ear
            return tone if front < 6 else tone2
        # the face
        if y == 0 or (y == 1 and x not in (3, 4)):
            return hair
        if y == 2 and x in (0, 7):
            return hair
        if y == 3 and x in (1, 2, 5, 6):
            return shade(hair, 0.9)  # eyebrows
        if y == 4:
            if x in (1, 6):
                return white
            if x in (2, 5):
                return iris
        if y == 5 and x in (0, 7):
            return blush
        if y == 5 and x in (3, 4):
            return tone2  # nose
        if y == 6 and x in (2, 5):
            return tone2
        if y == 6 and x in (3, 4):
            return lip
        if y == 7:
            return tone2
        return tone

    s.paint("head", 0, head)

    # second layer: hair that stands off the head, and what's worn on it
    def hat(face, x, y, w, h):
        if headwear == "beanie":
            knit = trim if (x + y) % 2 == 0 else shade(trim, 0.88)
            if face == "top":
                return shade(trim, 1.1) if 2 < x < 5 and 2 < y < 5 else knit
            if face == "bottom":
                return None
            if y < 2:
                return knit
            if y == 2:
                return shade(trim, 0.7)  # the folded brim
            if face == "front":
                return hair3 if y == 3 and x in (0, 1, 6, 7) else None
            if face == "back":
                return hair if y < 6 else None
            front = w - 1 - x if face == "right" else x
            return hair if y < 5 and front > 2 else None
        # headset over tousled hair
        band, cup = (34, 40, 50), (22, 26, 33)
        if face == "top":
            if y in (3, 4):
                return band
            return hair3 if (x * 3 + y) % 4 == 0 else hair
        if face == "bottom":
            return None
        if face == "front":
            if y == 0:
                return hair
            if y == 1:
                return hair3 if x in (1, 2, 5) else hair if x != 4 else None
            if y == 2:
                return hair if x in (0, 2, 6, 7) else None
            if (x, y) in ((0, 5), (0, 6), (1, 6)):
                return cup  # the mic boom
            if (x, y) == (2, 6):
                return trim
            return None
        if face == "back":
            return hair if y < 5 else hair2 if y == 5 and x not in (0, 7) else None
        front = w - 1 - x if face == "right" else x
        if 2 <= x <= 5 and 2 <= y <= 6:
            return trim if 3 <= x <= 4 and 3 <= y <= 5 else cup  # ear cup with a light in it
        if x in (3, 4) and y < 2:
            return band
        return hair if y < 4 and front > 1 else None

    s.paint("head", 1, hat)

    # ---------------------------------------------------------------- body
    def body(face, x, y, w, h):
        if face == "top":
            return hood2
        if face == "bottom":
            return jeans2
        if face in ("right", "left"):
            return hood3 if y == 11 else hood2
        if face == "back":
            if y < 4 and 1 <= x <= 6:
                return hood3 if y == 3 else hood2  # the hood lying on the back
            return hood3 if y == 11 else hood
        if y == 0 and x in (3, 4):
            return tone2  # neck
        if y < 2 and x in (2, 5):
            return hood2  # collar
        if x in (3, 4) and 1 <= y <= 10:
            return hood2 if x == 3 else hood  # zip
        if (x, y) in ((2, 2), (2, 3), (5, 2), (5, 3)):
            return white  # drawstrings
        if (x, y) in ((6, 4), (6, 5), (6, 6), (5, 6)):
            return ink  # a small J on the chest
        if 7 <= y <= 9 and x in (1, 2, 5, 6):
            return hood2  # pockets
        if y == 11:
            return hood3
        return hood

    s.paint("body", 0, body)

    def jacket(face, x, y, w, h):
        if face == "back" and y < 5 and 1 <= x <= 6:
            return shade(hoodie, 0.9) if y < 4 else hood3  # the hood, standing off the back
        if face in ("right", "left") and y < 2:
            return hood2
        if face == "front" and y == 0 and x in (1, 2, 5, 6):
            return hood2
        if face == "top" and (x < 3 or x > 4):
            return hood2
        return None

    s.paint("body", 1, jacket)

    # ---------------------------------------------------------------- arms
    def arm(face, x, y, w, h):
        if face == "top":
            return hood2
        if face == "bottom":
            return tone2
        side = face in ("right", "left", "back")
        if y >= 10:
            return tone2 if side else tone
        if y == 9:
            return hood3
        if y == 2:
            stripe = white if trim == hoodie else trim
            return stripe if not side else shade(stripe, 0.8)  # a stripe round the upper arm
        return hood2 if side else hood

    def sleeve(face, x, y, w, h):
        if face in ("top", "bottom"):
            return None
        return hood3 if y in (8, 9) else None  # a cuff that stands off the wrist

    for part in ("armR", "armL"):
        s.paint(part, 0, arm)
        s.paint(part, 1, sleeve)

    # ---------------------------------------------------------------- legs
    def leg(face, x, y, w, h):
        if face == "top":
            return trousers
        if face == "bottom":
            return sole
        side = face in ("right", "left", "back")
        if y == 11:
            return sole
        if y >= 9:
            if face == "front" and y == 9 and x in (1, 2):
                return trim  # laces
            return shoe
        if face == "front" and x == 0:
            return jeans2  # seam
        if y in (4, 5) and face == "front" and x in (1, 2):
            return shade(trousers, 1.12)  # worn knees
        return jeans2 if side else trousers

    def boot(face, x, y, w, h):
        if face in ("top", "bottom"):
            return None
        if y == 11:
            return sole
        if y in (9, 10):
            return trim if face == "front" and y == 9 and x in (1, 2) else shoe
        return None

    for part in ("legR", "legL"):
        s.paint(part, 0, leg)
        s.paint(part, 1, boot)
    return s


if __name__ == "__main__":
    for name, colour in ACCENTS.items():
        size = character(colour, (58, 40, 28), (226, 174, 128), (44, 56, 76), "headset", colour).save(f"skin-host-{name}.png")
        print(f"skin-host-{name}.png", size, "bytes")
    size = character((228, 232, 238), (226, 184, 92), (204, 148, 104), (54, 60, 72), "beanie", (236, 88, 88)).save("skin-friend.png")
    print("skin-friend.png", size, "bytes")
