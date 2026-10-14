#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs the debug build's live readout on the connected device or emulator and
# reads it back from logcat. With --tone the app listens to a synthesised sine
# through the debug seam, which is the only input path on a headless emulator;
# with --live it opens the real microphone.
#
#   tools/device/readout.sh --tone HZ [--amp A] [--rate R] [--a4 HZ] [--seconds N] [--expect NOTE]
#   tools/device/readout.sh --live [--a4 HZ] [--seconds N]
#
# Installs the debug APK first (offline Gradle), starts the activity with the
# launch extras InputSeam reads, waits, then prints every readout line the app
# logged. With --expect the last line must name that note (as the app spells
# it, for example A4 or "A#/Bb3") or the script exits 1. --a4 sets the A4 the
# detector measures against (default 440). Evidence goes under
# verification/<date>/, which is gitignored.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"

mode=""
hz=""
amp="0.1"
rate="48000"
seconds="6"
expect=""
a4="440"
while [ $# -gt 0 ]; do
    case "$1" in
        --tone) mode=tone; hz="${2:?--tone needs a frequency in Hz}"; shift ;;
        --live) mode=live ;;
        --amp) amp="${2:?--amp needs a value}"; shift ;;
        --rate) rate="${2:?--rate needs a value}"; shift ;;
        --seconds) seconds="${2:?--seconds needs a value}"; shift ;;
        --expect) expect="${2:?--expect needs a note label}"; shift ;;
        --a4) a4="${2:?--a4 needs a frequency in Hz}"; shift ;;
        -h|--help) sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
        *) echo "readout: unknown argument '$1'" >&2; exit 2 ;;
    esac
    shift
done
[ -n "$mode" ] || { echo "readout: pass --tone HZ or --live" >&2; exit 2; }

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
adb="${ANDROID_HOME:?set ANDROID_HOME}/platform-tools/adb"
[ -x "$adb" ] || adb=$(command -v adb) || { echo "readout: adb not found" >&2; exit 2; }

package=$(sed -n 's/^val placeholderApplicationId = "\(.*\)"/\1/p' app/build.gradle.kts).debug
activity="$package/ticktock.app.MainActivity"
tag="ticktock.debug"

"$adb" wait-for-device
./gradlew --offline --console=plain -q :app:installDebug

"$adb" shell am force-stop "$package"
"$adb" logcat -c
case "$mode" in
    tone)
        "$adb" shell am start -W -n "$activity" --ef ticktock.debug.tone_hz "$hz" --ef ticktock.debug.tone_amp "$amp" --ei ticktock.debug.tone_rate "$rate" --ef ticktock.debug.a4_hz "$a4" > /dev/null
        ;;
    live)
        "$adb" shell pm grant "$package" android.permission.RECORD_AUDIO
        "$adb" shell am start -W -n "$activity" --ez ticktock.debug.live true --ef ticktock.debug.a4_hz "$a4" > /dev/null
        ;;
esac
sleep "$seconds"

evidence="verification/$(date +%F)"
mkdir -p "$evidence"
log="$evidence/readout-$mode-$(date +%H%M%S).log"
"$adb" logcat -d -s "$tag:I" > "$log"
lines=$(grep -F 'readout note=' "$log" || true)
if [ -z "$lines" ]; then
    echo "readout: the app logged no readout line in $seconds s (log: $log)" >&2
    exit 1
fi
printf '%s\n' "$lines" | sed 's/^.*readout /readout /'
last=$(printf '%s\n' "$lines" | tail -1)
if [ -n "$expect" ]; then
    case "$last" in
        *"note=$expect "*) echo "readout: OK, last line names $expect (log: $log)" ;;
        *) echo "readout: FAILED, expected note=$expect in: $last" >&2; exit 1 ;;
    esac
fi
