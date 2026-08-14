"""
Renders the legacy density bitmaps for the ORBIS launcher icon.

Why this exists at all: minSdk is 26, so every supported device uses the
adaptive icon in `mipmap-anydpi-v26/` and never looks at these files. They still
ship inside the APK though, and they were still the stock Android Studio green
robot, so anything that reads the raw mipmap - an OEM launcher cache, a backup
tool, some share sheets - could surface a bugdroid for an app that has its own
mark. Regenerating them keeps one source of truth for the artwork.

The geometry is deliberately the same numbers as
`res/drawable/ic_launcher_foreground.xml`, scaled by LEGACY_SCALE. Legacy icons
have no safe zone - the whole square is visible - so the mark is drawn larger
here than the adaptive foreground, which has to survive a launcher mask.

Run:  python tools/generate_launcher_icons.py
"""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

# --- the mark, in the same 108-unit space as the vector drawables ------------

VIEWPORT = 108.0
CENTRE = 54.0

# Near 3:1, and an orb wider than the semi-minor axis, so the orb occludes the
# near and far sides of the path. Both are what stop the mark reading as an eye.
ORBIT_RX = 27.0
ORBIT_RY = 9.5
ORBIT_STROKE = 5.0
ORBIT_ROTATION_DEG = 28.0  # anticlockwise; matches android:rotation="-28"

BODY_R = 5.0
ORB_R = 10.0

BACKGROUND_TOP = (16, 87, 78)      # #10574E
BACKGROUND_MID = (10, 59, 53)      # #0A3B35
BACKGROUND_BOTTOM = (6, 42, 38)    # #062A26
ORBIT_COLOUR = (63, 191, 169)      # #3FBFA9
BODY_COLOUR = (242, 191, 72)       # #F2BF48
ORB_COLOUR = (125, 240, 221)       # #7DF0DD

# An adaptive icon's visible area is roughly the central 72 of its 108 viewport.
# Scaling by 108/72 renders the same mark at the size a legacy icon expects,
# pulled in slightly so it does not crowd the edge of the tile.
LEGACY_SCALE = (VIEWPORT / 72.0) * 0.93

# Supersample, then downsample: PIL has no antialiased vector rasteriser, and a
# 48px icon drawn directly comes out with visibly stepped curves.
SUPERSAMPLE = 8

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def _gradient(size: int) -> Image.Image:
    """Diagonal three-stop teal field, matching ic_launcher_background.xml."""
    # Built small and upscaled: it is a smooth ramp, so interpolation is exact
    # enough and this avoids a per-pixel loop at 1536px.
    steps = 128
    ramp = Image.new("RGB", (steps, steps))
    pixels = ramp.load()
    for y in range(steps):
        for x in range(steps):
            # Position along the top-left -> bottom-right diagonal.
            t = (x + y) / (2.0 * (steps - 1))
            if t <= 0.55:
                u = t / 0.55
                a, b = BACKGROUND_TOP, BACKGROUND_MID
            else:
                u = (t - 0.55) / 0.45
                a, b = BACKGROUND_MID, BACKGROUND_BOTTOM
            pixels[x, y] = tuple(round(a[i] + (b[i] - a[i]) * u) for i in range(3))
    return ramp.resize((size, size), Image.LANCZOS)


def _mark_layer(size: int) -> Image.Image:
    """The orbit ring and its body, rotated together."""
    scale = size / VIEWPORT * LEGACY_SCALE
    offset = size / 2.0 - CENTRE * scale

    def px(v: float) -> float:
        return v * scale + offset

    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    draw.ellipse(
        [px(CENTRE - ORBIT_RX), px(CENTRE - ORBIT_RY),
         px(CENTRE + ORBIT_RX), px(CENTRE + ORBIT_RY)],
        outline=ORBIT_COLOUR + (255,),
        width=max(1, round(ORBIT_STROKE * scale)),
    )

    # The body sits on the path at the end of the semi-major axis, and has to
    # rotate with the ring - so it is drawn here, before the rotation.
    bx, by = CENTRE + ORBIT_RX, CENTRE
    draw.ellipse(
        [px(bx - BODY_R), px(by - BODY_R), px(bx + BODY_R), px(by + BODY_R)],
        fill=BODY_COLOUR + (255,),
    )

    return layer.rotate(ORBIT_ROTATION_DEG, resample=Image.BICUBIC, center=(size / 2, size / 2))


def render(size: int, round_icon: bool) -> Image.Image:
    big = size * SUPERSAMPLE

    icon = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    icon.paste(_gradient(big), (0, 0))

    if round_icon:
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, big - 1, big - 1], fill=255)
        icon.putalpha(mask)

    icon.alpha_composite(_mark_layer(big))

    # The centre orb is on the axis of rotation, so it is composited last and
    # unrotated - and last is also what puts it in front of the ring.
    scale = big / VIEWPORT * LEGACY_SCALE
    offset = big / 2.0 - CENTRE * scale
    r = ORB_R * scale
    cx = CENTRE * scale + offset
    orb = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ImageDraw.Draw(orb).ellipse(
        [cx - r, cx - r, cx + r, cx + r], fill=ORB_COLOUR + (255,)
    )
    icon.alpha_composite(orb)

    return icon.resize((size, size), Image.LANCZOS)


def main() -> None:
    res = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
    if not res.is_dir():
        raise SystemExit(f"resource directory not found: {res}")

    written = 0
    for density, size in DENSITIES.items():
        target = res / f"mipmap-{density}"
        target.mkdir(parents=True, exist_ok=True)
        for name, is_round in (("ic_launcher", False), ("ic_launcher_round", True)):
            # Overwrite the existing .webp rather than adding a .png: two files
            # with the same base name in one resource directory is a build error.
            path = target / f"{name}.webp"
            render(size, is_round).save(path, "WEBP", lossless=True, quality=100)
            written += 1
            print(f"  {path.relative_to(res.parent.parent.parent.parent)}  {size}x{size}")

    print(f"\n{written} files written.")


if __name__ == "__main__":
    main()
