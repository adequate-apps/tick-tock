# BellCurve

BellCurve is an Android practice app for saxophone and other wind players. It has a tuner that keeps per-note intonation statistics (the bell curve of your readings for each note), a metronome, a drone, pitch pipes and a recording deck.

This repository holds a ground-up rebuild of the app. It starts with the app icon; the specification and the code follow in milestones.

## Icon

`assets/icon.svg` and `assets/icon-monochrome.svg` are the sources. `python3 scripts/gen_icon.py` renders every launcher, adaptive, themed, splash and web size from them. It needs `rsvg-convert` (librsvg) and ImageMagick's `magick`.
