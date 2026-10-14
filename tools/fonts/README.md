# Fonts

IBM Plex Sans (400, 500, 600, 700) and IBM Plex Mono (400, 500, 600) are bundled in
`design/src/main/res/font/` under the SIL Open Font License 1.1 (`IBM-Plex-OFL-1.1.txt`). These are
the weights the type scale binds and no more.

`stage.sh <font directory>` copies and renames the seven files from an installed IBM Plex package and
checks them against `MANIFEST.sha256`, which pins the exact builds. To check the bundled files alone:
`cd design/src/main/res/font && sha256sum -c ../../../../../tools/fonts/MANIFEST.sha256`.
