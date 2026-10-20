#!/usr/bin/env python3
"""Generate a synthetic 200x228 1-bit frame for the Screen Mirror watchapp.

The output is a sequence of tile records:
    uint16 little-endian tile index, followed by 8 bytes (one byte per row)
This is exactly the `DATA` payload of KIND_FRAME.

Usage:
    python tools/generate_test_frame.py --out watchapp/test_frame.bin
"""

from __future__ import annotations

import argparse
import struct
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

WIDTH = 200
HEIGHT = 228
TILE = 8


def load_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        "C:/Windows/Fonts/segoeui.ttf",
        "C:/Windows/Fonts/arial.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    for candidate in candidates:
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


def make_image() -> Image.Image:
    image = Image.new("L", (WIDTH, HEIGHT), 255)
    draw = ImageDraw.Draw(image)
    font_big = load_font(26)
    font = load_font(16)
    font_small = load_font(12)

    # Phone status bar.
    draw.rectangle((0, 0, WIDTH, 22), fill=30)
    draw.text((6, 3), "9:41", font=font_small, fill=255)
    draw.text((WIDTH - 62, 3), "5G  84%", font=font_small, fill=255)

    # App header.
    draw.text((10, 31), "Messages", font=font_big, fill=0)
    draw.line((0, 64, WIDTH, 64), fill=80, width=1)

    # Conversation list.
    rows = [
        ("Ada", "See you at 8?", "now"),
        ("Build bot", "Build #482 passed", "2m"),
        ("Mum", "Call me when free", "18m"),
        ("Pebble", "Your watch is ready", "1h"),
        ("Weather", "Rain later today", "3h"),
    ]
    y = 72
    for name, message, when in rows:
        draw.ellipse((10, y, 42, y + 32), outline=0, width=2)
        draw.text((18, y + 7), name[:1], font=font, fill=0)
        draw.text((50, y + 1), name, font=font, fill=0)
        draw.text((50, y + 19), message, font=font_small, fill=70)
        draw.text((WIDTH - 30, y + 1), when, font=font_small, fill=100)
        draw.line((50, y + 39, WIDTH, y + 39), fill=190, width=1)
        y += 40

    # A dithered gradient strip to exercise the encoder.
    for x in range(WIDTH):
        shade = int(255 * x / (WIDTH - 1))
        draw.line((x, HEIGHT - 15, x, HEIGHT - 1), fill=shade)

    return image


def pack_tiles(image: Image.Image) -> bytes:
    pixels = image.load()
    tiles_x = (WIDTH + TILE - 1) // TILE
    tiles_y = (HEIGHT + TILE - 1) // TILE
    out = bytearray()
    for ty in range(tiles_y):
        for tx in range(tiles_x):
            index = ty * tiles_x + tx
            data = bytearray(TILE)
            for row in range(TILE):
                y = ty * TILE + row
                if y >= HEIGHT:
                    break
                bits = 0
                for col in range(TILE):
                    x = tx * TILE + col
                    if x < WIDTH and pixels[x, y] >= 128:
                        # Pebble's 1-bit bitmaps are LSB-first within each byte.
                        bits |= 1 << col
                data[row] = bits
            out += struct.pack("<H", index)
            out += data
    return bytes(out)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="watchapp/test_frame.bin")
    parser.add_argument("--preview", default="watchapp/test_frame_preview.png")
    args = parser.parse_args()

    image = make_image()
    image.convert("1").save(args.preview)
    payload = pack_tiles(image)
    Path(args.out).write_bytes(payload)
    tiles = (WIDTH // TILE) * ((HEIGHT + TILE - 1) // TILE)
    print(f"wrote {args.out}: {len(payload)} bytes, {tiles} tiles, preview={args.preview}")


if __name__ == "__main__":
    main()
