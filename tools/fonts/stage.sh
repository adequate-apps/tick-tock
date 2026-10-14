#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Copy the IBM Plex weights the type scale uses from an installed font directory
# into design/src/main/res/font and check them against MANIFEST.sha256. The
# manifest is the pin: a different build of the fonts fails here rather than
# shipping unnoticed.
#
#   tools/fonts/stage.sh /usr/share/fonts/TTF
set -euo pipefail

src="${1:?usage: stage.sh <directory holding IBMPlexSans-*.ttf and IBMPlexMono-*.ttf>}"
here="$(cd "$(dirname "$0")" && pwd)"
dest="$here/../../design/src/main/res/font"

# Resource names must be lower-case; weights are the four Sans and three Mono the scale binds.
declare -A files=(
  ["IBMPlexSans-Regular.ttf"]="ibm_plex_sans_regular.ttf"
  ["IBMPlexSans-Medium.ttf"]="ibm_plex_sans_medium.ttf"
  ["IBMPlexSans-SemiBold.ttf"]="ibm_plex_sans_semibold.ttf"
  ["IBMPlexSans-Bold.ttf"]="ibm_plex_sans_bold.ttf"
  ["IBMPlexMono-Regular.ttf"]="ibm_plex_mono_regular.ttf"
  ["IBMPlexMono-Medium.ttf"]="ibm_plex_mono_medium.ttf"
  ["IBMPlexMono-SemiBold.ttf"]="ibm_plex_mono_semibold.ttf"
)

mkdir -p "$dest"
for from in "${!files[@]}"; do
  cp -- "$src/$from" "$dest/${files[$from]}"
done

cd "$dest"
sha256sum -c "$here/MANIFEST.sha256"
