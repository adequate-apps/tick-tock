#!/usr/bin/env python3
"""
Render the BellCurve app icon assets from the SVG sources.

Sources (edit these, then re-run this script):
  assets/icon.svg              full art on the 108-unit adaptive-icon canvas,
                               background included
  assets/icon-monochrome.svg   one-colour silhouette for Android themed icons

Outputs (overwritten in place):
  assets/icon.png                     1024x1024 RGB   launcher / store icon
  assets/android-icon-foreground.png   512x512  RGBA  adaptive foreground layer
  assets/android-icon-background.png   512x512  RGB   adaptive background layer
  assets/android-icon-monochrome.png   432x432  RGBA  themed-icon mask
  assets/favicon.png                    48x48   RGB   web
  assets/splash-icon.png              1024x1024 RGBA  splash centrepiece
  docs/assets/bellcurve.png           1024x1024 RGB   project page image

Requires rsvg-convert (librsvg) and ImageMagick's magick on PATH.

Android launchers show the central 72 of the 108 units under a mask, so the
square icons are cropped to that window; the adaptive layers keep the full
canvas and let the launcher crop.
"""

import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'assets'
SOURCE = ASSETS / 'icon.svg'
MONO_SOURCE = ASSETS / 'icon-monochrome.svg'

FULL_VIEW = 'viewBox="0 0 108 108"'
LAUNCHER_VIEW = 'viewBox="18 18 72 72"'
BACKGROUND = re.compile(r'\s*<path fill="(#[0-9A-Fa-f]{6})" d="M0 0h108v108H0z"/>')


def render(svg_text: str, out: Path, size: int, flatten: str | None = None) -> None:
    with tempfile.NamedTemporaryFile('w', suffix='.svg', delete=False) as tmp:
        tmp.write(svg_text)
    try:
        subprocess.run(['rsvg-convert', '-w', str(size), '-h', str(size), tmp.name, '-o', str(out)], check=True)
        if flatten:
            subprocess.run(['magick', str(out), '-background', flatten, '-alpha', 'remove',
                            '-alpha', 'off', str(out)], check=True)
    finally:
        Path(tmp.name).unlink()


def main() -> None:
    art = SOURCE.read_text()
    match = BACKGROUND.search(art)
    if FULL_VIEW not in art or not match:
        raise SystemExit(f'{SOURCE} must use {FULL_VIEW} and start with a full-canvas background path')
    background = match.group(1)
    foreground = BACKGROUND.sub('', art, count=1)
    launcher = art.replace(FULL_VIEW, LAUNCHER_VIEW)
    background_only = (f'<svg xmlns="http://www.w3.org/2000/svg" {FULL_VIEW}>'
                       f'<path fill="{background}" d="M0 0h108v108H0z"/></svg>')

    render(launcher, ASSETS / 'icon.png', 1024, flatten=background)
    render(foreground, ASSETS / 'android-icon-foreground.png', 512)
    render(background_only, ASSETS / 'android-icon-background.png', 512, flatten=background)
    render(MONO_SOURCE.read_text(), ASSETS / 'android-icon-monochrome.png', 432)
    render(launcher, ASSETS / 'favicon.png', 48, flatten=background)
    render(foreground, ASSETS / 'splash-icon.png', 1024)
    render(launcher, ROOT / 'docs' / 'assets' / 'bellcurve.png', 1024, flatten=background)
    print('icon assets written')


if __name__ == '__main__':
    main()
