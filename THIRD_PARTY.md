# Third-party material

TickTock is GPL-3.0-or-later (see `LICENSE`). The following third-party material is included or
used, each under its own licence.

## MuseScore instrument range data

The keyed and extended instrument ranges in the catalogue are generated from the instrument
definitions published by the MuseScore project (`instruments.xml`), licensed under the GNU General
Public License version 3. The keyed range comes from its early-amateur tier and the extended range
from its professional tier; corrections are recorded with a reason in the project's own overlay
file (`core/src/main/resources/ranges/overlay.json`). The generated base layer
(`core/src/main/resources/ranges/base.json`) carries a header crediting MuseScore and naming the
generator (`tools/gen-ranges/gen_ranges.py`) and the sha256 of the XML it was built from. The
MuseScore XML itself is a build input and is kept outside this repository.

Licence: GPL-3.0. <https://musescore.org/>

## IBM Plex

IBM Plex Sans and IBM Plex Mono are bundled in `design/src/main/res/font/` under the SIL Open Font
License, Version 1.1. The licence text is in `tools/fonts/IBM-Plex-OFL-1.1.txt`, and
`tools/fonts/MANIFEST.sha256` pins the bundled files.

Copyright 2017 IBM Corp., with Reserved Font Name "Plex". Licence: OFL-1.1.

## GoogleTest

The host C++ test build (added with the audio engine) uses GoogleTest, BSD-3-Clause. It is a test
dependency only and is never shipped in the app.

## Build and test libraries

Gradle, the Android Gradle Plugin, Kotlin, Jetpack Compose and AndroidX, kotlinx.coroutines, JUnit,
Robolectric and Roborazzi are used under their respective licences (Apache-2.0, EPL-1.0 for JUnit,
MIT for Robolectric). Every artifact and its sha256 is listed in `gradle/verification-metadata.xml`.
