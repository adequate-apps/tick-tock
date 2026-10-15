#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Starts the debug build's drone and metronome on the connected device or
# emulator through the real engine (control thread, JNI bridge, AAudio) and
# reads the output events back from logcat: the stream that opened, every
# beat, the counters and the render callback's frame clock.
#
#   tools/device/output.sh [--drone MIDI] [--metro BPM] [--seconds N] [--expect-beats K]
#
# At least one of --drone and --metro. Installs the debug APK first (offline
# Gradle), starts the activity with the launch extras OutputSeam reads, waits,
# then prints the output lines the app logged. The run passes when a stream
# opened, the render callback's frame count advanced, and (with --metro) at
# least K beats were reported; K defaults to what BPM gives in N seconds less
# two. Evidence goes under verification/<date>/, which is gitignored.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"

drone=""
metro=""
seconds="8"
expect_beats=""
while [ $# -gt 0 ]; do
    case "$1" in
        --drone) drone="${2:?--drone needs a MIDI note number}"; shift ;;
        --metro) metro="${2:?--metro needs a tempo in bpm}"; shift ;;
        --seconds) seconds="${2:?--seconds needs a value}"; shift ;;
        --expect-beats) expect_beats="${2:?--expect-beats needs a count}"; shift ;;
        -h|--help) sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
        *) echo "output: unknown argument '$1'" >&2; exit 2 ;;
    esac
    shift
done
[ -n "$drone" ] || [ -n "$metro" ] || { echo "output: pass --drone MIDI and/or --metro BPM" >&2; exit 2; }

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
adb="${ANDROID_HOME:?set ANDROID_HOME}/platform-tools/adb"
[ -x "$adb" ] || adb=$(command -v adb) || { echo "output: adb not found" >&2; exit 2; }

# The default application id (decision 17), plus the debug suffix.
base_id=$(sed -n 's/^ *\.getOrElse("\(.*\)")$/\1/p' app/build.gradle.kts)
[ -n "$base_id" ] || { echo "output: no application id found in app/build.gradle.kts" >&2; exit 2; }
package="$base_id.debug"
activity="$package/ticktock.app.MainActivity"
tag="ticktock.debug"

extras=()
[ -z "$drone" ] || extras+=(--ei ticktock.debug.drone_midi "$drone")
[ -z "$metro" ] || extras+=(--ei ticktock.debug.metro_bpm "$metro")

"$adb" wait-for-device
./gradlew --offline --console=plain -q :app:installDebug

"$adb" shell am force-stop "$package"
"$adb" logcat -c
"$adb" shell am start -W -n "$activity" "${extras[@]}" > /dev/null
sleep "$seconds"
# Backgrounding the app stops the tools; the Closed event and the stop commands land in the log too.
"$adb" shell input keyevent KEYCODE_HOME
sleep 1

evidence="verification/$(date +%F)"
mkdir -p "$evidence"
log="$evidence/output-$(date +%H%M%S).log"
"$adb" logcat -d -s "$tag:I" > "$log"
lines=$(grep -F 'output ' "$log" | sed 's/^.*output /output /' || true)
if [ -z "$lines" ]; then
    echo "output: the app logged no output line in $seconds s (log: $log)" >&2
    exit 1
fi
printf '%s\n' "$lines" | grep -v -E '^output event=Beat ' | head -40
beats=$(printf '%s\n' "$lines" | grep -c -E '^output event=Beat ' || true)
echo "output: $beats beat events (log: $log)"

status=0
if ! printf '%s\n' "$lines" | grep -q -E '^output event=Opened '; then
    echo "output: FAILED, no stream opened" >&2
    status=1
fi
frames=$(printf '%s\n' "$lines" | grep -E '^output diagnostics ' | sed -n 's/.*frames=\([0-9]*\).*/\1/p' | tail -1)
if [ -z "$frames" ] || [ "$frames" -le 0 ]; then
    echo "output: FAILED, the render callback's frame count did not advance (frames=${frames:-none})" >&2
    status=1
fi
if [ -n "$metro" ]; then
    if [ -z "$expect_beats" ]; then
        expect_beats=$(( metro * (seconds - 2) / 60 ))
        [ "$expect_beats" -ge 1 ] || expect_beats=1
    fi
    if [ "$beats" -lt "$expect_beats" ]; then
        echo "output: FAILED, $beats beats in $seconds s at $metro bpm, expected at least $expect_beats" >&2
        status=1
    fi
fi
if ! printf '%s\n' "$lines" | grep -q -E '^output event=Closed'; then
    echo "output: FAILED, the stream did not close when the app was backgrounded" >&2
    status=1
fi
[ "$status" -eq 0 ] && echo "output: OK, stream opened, ${frames} frames rendered, $beats beats, closed on background"
exit "$status"
