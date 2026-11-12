#!/usr/bin/env python3
"""Paints the two player skins the website's 3D characters wear (website/media/skin-host.png and skin-friend.png).

They are original pixel art in the standard 64x64 player-skin layout (not Mojang's textures): a hoodie in the given
colour with a small J on the chest, dark trousers and shoes.

  python3 scripts/gen-website-skins.py
"""
import pathlib
import struct
import zlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "website/media"


def skin(hoodie, hair, tone, name):
    px = [[(0, 0, 0, 0)] * 64 for _ in range(64)]

    def shade(colour, by):
        return tuple(max(0, min(255, round(c * by))) for c in colour)

    def rect(x, y, w, h, colour, noise=True):
        for j in range(y, y + h):
            for i in range(x, x + w):
                # a little grain, the same every run, so flat areas look like fabric and not like plastic
                grain = 1 + (((i * 7 + j * 13 + i * j) % 5) - 2) * 0.018 if noise else 1
                px[j][i] = shade(colour, grain) + (255,)

    dark, darker = shade(hoodie, 0.8), shade(hoodie, 0.64)
    tone2 = shade(tone, 0.86)
    trousers, trousers2 = (40, 52, 70), (30, 40, 56)
    shoe, sole, ink = (22, 26, 33), (214, 220, 228), (12, 22, 16)

    # head: top, bottom, right, front, left, back
    rect(8, 0, 8, 8, hair)
    rect(16, 0, 8, 8, tone2)
    for x in (0, 16):  # the two sides: hair down to the ears, a sideburn, then skin
        rect(x, 8, 8, 8, tone)
        rect(x, 8, 8, 4, hair)
        rect(x + (5 if x == 0 else 0), 12, 3, 2, hair)
    rect(24, 8, 8, 8, hair)
    rect(24, 14, 8, 2, tone2)
    rect(8, 8, 8, 8, tone)  # the face
    rect(8, 8, 8, 2, hair)
    rect(8, 10, 1, 2, hair)
    rect(15, 10, 1, 1, hair)
    rect(12, 10, 2, 1, hair)
    rect(9, 12, 2, 1, (250, 250, 250), False)
    rect(13, 12, 2, 1, (250, 250, 250), False)
    rect(10, 12, 1, 1, (40, 60, 90), False)
    rect(13, 12, 1, 1, (40, 60, 90), False)
    rect(11, 13, 2, 1, tone2)
    rect(11, 14, 2, 1, shade(tone, 0.62))

    # body: top, bottom, right, front, left, back
    rect(20, 16, 8, 4, dark)
    rect(28, 16, 8, 4, trousers)
    rect(16, 20, 4, 12, dark)
    rect(28, 20, 4, 12, dark)
    rect(20, 20, 8, 12, hoodie)
    rect(23, 20, 2, 2, tone)  # neck
    rect(22, 20, 1, 3, darker)  # hood strings
    rect(25, 20, 1, 3, darker)
    for x, y in ((24, 23), (24, 24), (24, 25), (23, 26), (22, 26), (22, 25)):  # the J
        rect(x, y, 1, 1, ink, False)
    rect(21, 27, 6, 3, dark)  # pocket
    rect(20, 31, 8, 1, darker)
    rect(32, 20, 8, 12, hoodie)
    rect(33, 20, 6, 4, dark)  # the hood, hanging down the back
    rect(34, 24, 4, 1, dark)
    rect(32, 31, 8, 1, darker)

    def arm(x, y):  # x, y: the corner of the arm's block in the layout
        rect(x + 4, y, 4, 4, dark)
        rect(x + 8, y, 4, 4, tone2)
        for side in range(4):
            sx = x + side * 4
            rect(sx, y + 4, 4, 12, hoodie if side in (1, 3) else dark)
            rect(sx, y + 12, 4, 1, darker)
            rect(sx, y + 13, 4, 3, tone if side in (1, 3) else tone2)

    def leg(x, y):
        rect(x + 4, y, 4, 4, trousers)
        rect(x + 8, y, 4, 4, shoe)
        for side in range(4):
            sx = x + side * 4
            rect(sx, y + 4, 4, 12, trousers if side in (1, 3) else trousers2)
            rect(sx, y + 13, 4, 2, shoe)
            rect(sx, y + 15, 4, 1, sole)

    arm(40, 16)
    arm(32, 48)
    leg(0, 16)
    leg(16, 48)

    rows = bytearray()
    for row in px:
        rows.append(0)
        for pixel in row:
            rows += bytes(pixel)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))
    (OUT / name).write_bytes(png)
    print(name, len(png), "bytes")


if __name__ == "__main__":
    skin((61, 220, 132), (58, 40, 28), (226, 174, 128), "skin-host.png")
    skin((79, 157, 247), (226, 184, 92), (198, 140, 96), "skin-friend.png")
