#!/usr/bin/env python3
"""Writes the corner masks Draw.roundRect uses: one white, anti-aliased disc per corner radius in screen pixels
(GUI radius 1-10 times GUI scale 1-6), into src/main/resources/assets/javahost/textures/gui/round/<radius>.png.
A rounded rectangle is then four quarter-discs and three plain bands instead of a hundred thin strips.

  python3 scripts/gen-round.py
"""
import math
import pathlib
import struct
import zlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/javahost/textures/gui/round"


def png(size, alpha):
    rows = bytearray()
    for y in range(size):
        rows.append(0)
        for x in range(size):
            rows += bytes((255, 255, 255, alpha(x, y)))

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for radius in sorted({r * s for r in range(1, 11) for s in range(1, 7)}):
        def alpha(x, y, radius=radius):
            distance = math.hypot(x + 0.5 - radius, y + 0.5 - radius)
            return round(max(0.0, min(1.0, radius - distance + 0.5)) * 255)
        (OUT / f"{radius}.png").write_bytes(png(radius * 2, alpha))
    print(len(list(OUT.glob("*.png"))), "masks,", sum(f.stat().st_size for f in OUT.glob("*.png")), "bytes")
