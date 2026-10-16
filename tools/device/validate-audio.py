#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the deterministic corpus and run Android accuracy, storage and hop budgets offline."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.adequateapps.metronome.debug"


def snapshot():
    paths = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT
    ).split(b"\0")
    records = []
    for name in sorted(set(paths) - {b""}):
        path = ROOT / os.fsdecode(name)
        records.append({"path": os.fsdecode(name), "sha256":
                        hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else "missing"})
    encoded = json.dumps(records, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest(), records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="One explicitly selected adb device")
    args = parser.parse_args()
    java = Path(os.environ["JAVA_HOME"]) / "bin/java"
    version = subprocess.run([str(java), "-version"], capture_output=True, text=True, check=True)
    if not re.search(r'version "21\.', version.stderr):
        raise RuntimeError("JDK 21 is required")
    adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
    target = [adb, "-s", args.serial]
    if subprocess.check_output(target + ["get-state"], text=True).strip() != "device":
        raise RuntimeError("selected Android device is not ready")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    evidence = ROOT / "build/audio-validation" / stamp
    evidence.mkdir(parents=True)
    print(f"Evidence: {evidence}", flush=True)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    digest, records = snapshot()
    (evidence / "source.json").write_text(json.dumps(
        {"commit": commit, "snapshotSha256": digest, "files": records}, indent=2) + "\n")
    (evidence / "working.patch").write_bytes(subprocess.check_output(["git", "diff", "HEAD", "--binary"], cwd=ROOT))
    (evidence / "host.txt").write_text(Path("/proc/cpuinfo").read_text() + "\nload=" + Path("/proc/loadavg").read_text())
    (evidence / "device-properties.txt").write_bytes(subprocess.check_output(target + ["shell", "getprop"]))

    def run(name, command, timeout=1200):
        print(name, flush=True)
        with (evidence / (name + ".log")).open("wb") as output:
            result = subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"{name} failed; see {evidence / (name + '.log')}")
        return (evidence / (name + ".log")).read_text()

    def unchanged():
        if snapshot()[0] != digest or subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() != commit:
            raise RuntimeError("source changed during validation; this snapshot cannot pass")

    assets = evidence / "assets"
    corpus = assets / "native-audio"
    run("native-corpus", ["./gradlew", "--offline", ":core:jvmTest", "--tests",
        "ticktock.core.detect.NativeBleedFixtureTest", f"-Pticktock.audioFixtureOutputDir={corpus}",
        f"-Pticktock.sourceCommit={commit}", f"-Pticktock.sourceSnapshotSha256={digest}"])
    if len(list(corpus.glob("*/manifest.json"))) != 20:
        raise RuntimeError("native corpus did not export all twenty passing cases")
    run("build", ["./gradlew", "--offline", ":app:assembleDebug", ":app:assembleDebugAndroidTest",
                  f"-Pticktock.audioAssetsDir={assets}"])
    unchanged()
    apks = [ROOT / "app/build/outputs/apk/debug/app-debug.apk",
            ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
    (evidence / "apks.json").write_text(json.dumps({str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                                  for p in apks}, indent=2) + "\n")
    for index, apk in enumerate(apks):
        output = run(f"install-{index}", target + ["install", "-r", "-t", str(apk)])
        if "Success" not in output:
            raise RuntimeError("Android did not confirm APK installation")
    tests = [
        ("pitch", "DeterministicAudioTest#pitchMatrix", ["pitch-matrix.csv", "pitch-failures.txt", "device.txt"]),
        ("native", "NativeAudioReplayTest#nativeCorpusReplay", ["native-replay.csv", "native-failures.txt"]),
        ("budget", "DeterministicAudioTest#perHopBudget", ["hop-timings.csv", "hop-budget.csv"]),
    ]
    for name, selection, files in tests:
        (evidence / (name + "-host-load.txt")).write_text(Path("/proc/loadavg").read_text())
        output = run(name, target + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
            "ticktock.app." + selection, "-e", "sourceCommit", commit, "-e", "sourceSnapshotSha256", digest,
            "-e", "evidenceRun", stamp,
            PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"])
        for file in files:
            result = subprocess.run(target + ["exec-out", "run-as", PACKAGE, "cat", "files/audio-validation/" + stamp + "/" + file],
                                    capture_output=True)
            if result.returncode == 0:
                (evidence / file).write_bytes(result.stdout)
        if not re.search(r"OK \(1 test\)", output) or "INSTRUMENTATION_CODE: -1" not in output:
            raise RuntimeError(f"{name} did not report exactly one passing instrumentation test")
        if any(not (evidence / file).is_file() for file in files):
            raise RuntimeError(f"{name} passed without required evidence")
    unchanged()
    (evidence / "verdict.txt").write_text("PASS: 672 pitch cases, 20 native PCM cases, 3000 measured hops.\n"
        "Android emulator/device software evidence; physical acoustic and reference-phone checks remain separate.\n")
    print((evidence / "verdict.txt").read_text(), flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.SubprocessError) as failure:
        print(f"audio-validation: FAIL: {failure}", file=sys.stderr)
        sys.exit(1)
