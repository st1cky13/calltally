#!/usr/bin/env python3
"""Draws the app icons into static/. Needs ImageMagick; only for remaking them, the server doesn't use it.

The handset is the "call" icon from Google's Material Icons (Apache License 2.0).
"""
import subprocess
import tempfile
from pathlib import Path

GREEN = "#0f6b5c"
HANDSET = ("M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57"
           ".55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57"
           ".11.35.03.74-.25 1.02l-2.2 2.2z")
OUT = Path(__file__).resolve().parent.parent / "static"


def make(name, pixels, radius, share):
    """share is how much of the icon's width the handset takes."""
    scale = 1024 * share / 18  # the handset is 18 units wide, centred on (12, 12)
    move = 512 - 12 * scale
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">'
           f'<rect width="1024" height="1024" rx="{radius}" fill="{GREEN}"/>'
           f'<path transform="translate({move} {move}) scale({scale})" d="{HANDSET}" fill="#fff"/></svg>')
    with tempfile.TemporaryDirectory() as folder:
        source = Path(folder) / "icon.svg"
        source.write_text(svg)
        subprocess.run(["magick", "-background", "none", f"msvg:{source}", "-resize", f"{pixels}x{pixels}",
                        f"PNG32:{OUT / name}"], check=True)


OUT.mkdir(exist_ok=True)
make("icon-192.png", 192, 225, 0.62)
make("icon-512.png", 512, 225, 0.62)
# Android may crop this one to a circle, so the handset stays inside the middle of the square.
make("icon-512-maskable.png", 512, 0, 0.5)
