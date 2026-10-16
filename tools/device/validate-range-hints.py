#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Verify installed sandbox hints with PCM, pointer input, themes and font sizes.

Build/install separately with ticktock.applicationIdOverride set to
com.adequateapps.metronome.coldproof. Only this fixed emulator sandbox is reset.
Installed APK bytes are verified first. The original font setting is restored
on success or failure, and all case evidence is retained outside sandbox data.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.adequateapps.metronome.coldproof.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = "ticktock.app.RangeHintDeviceTest#rangeHintFromPcmAndPointer"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def snapshot():
    names = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT
    ).split(b"\0")
    files = [{"path": os.fsdecode(name), "sha256": sha256(ROOT / os.fsdecode(name))}
             for name in sorted(set(names) - {b""})]
    return hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(), files


def properties(text):
    return dict(line.split("=", 1) for line in text.splitlines()
                if line and not line.startswith(("#", "!")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--app-apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    args = parser.parse_args()
    target = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", args.serial]
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    evidence = ROOT / "build/range-hint-validation" / stamp
    evidence.mkdir(parents=True)
    print(f"Evidence: {evidence}", flush=True)

    def run(name, command, timeout=120, binary=False):
        result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=timeout)
        (evidence / (name + ".log")).write_bytes(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError(f"{name} failed; see retained log")
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")

    def shell(name, *arguments, timeout=120):
        return run(name, target + ["shell", *arguments], timeout)

    if run("device-state", target + ["get-state"]).strip() != "device":
        raise RuntimeError("selected emulator is not ready")
    if shell("emulator-check", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("range hint proof only permits an emulator")
    shell("device-properties", "getprop")
    expected_runner = f"instrumentation:{RUNNER} (target={PACKAGE})"
    if expected_runner not in shell("instrumentation", "pm", "list", "instrumentation").splitlines():
        raise RuntimeError("the fixed sandbox instrumentation is not installed")
    hashes = {}
    for name, package, expected in (("app", PACKAGE, args.app_apk), ("test", PACKAGE + ".test", args.test_apk)):
        if not expected.is_file():
            raise RuntimeError(f"expected {name} APK is missing")
        paths = shell(name + "-path", "pm", "path", package).splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:/data/app/"):
            raise RuntimeError(f"unsupported {name} package layout")
        retained = evidence / (name + ".apk")
        run(name + "-pull", target + ["pull", paths[0].removeprefix("package:"), str(retained)])
        hashes[name] = sha256(retained)
        if hashes[name] != sha256(expected):
            raise RuntimeError(f"installed {name} APK differs from the expected artifact")

    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    digest, files = snapshot()
    (evidence / "source.json").write_text(json.dumps({"commit": commit, "snapshotSha256": digest,
        "files": files, "apkSha256": hashes, "serial": args.serial}, indent=2) + "\n")
    (evidence / "working.patch").write_bytes(subprocess.check_output(["git", "diff", "HEAD", "--binary"], cwd=ROOT))
    original_font = shell("original-font", "settings", "get", "system", "font_scale").strip()
    results = []
    try:
        for rate in (44100, 48000):
            for theme in ("GRIDLINE", "PIT"):
                for scale in ("1.0", "2.0"):
                    case = f"{rate}-{theme.lower()}-fs{scale}"
                    print(case, flush=True)
                    folder = evidence / case
                    folder.mkdir()
                    shell(case + "-stop", "am", "force-stop", PACKAGE)
                    if shell(case + "-reset", "pm", "clear", PACKAGE).strip() != "Success":
                        raise RuntimeError("dedicated sandbox reset failed")
                    shell(case + "-font", "settings", "put", "system", "font_scale", scale)
                    output = shell(case, "am", "instrument", "-w", "-r", "-e", "class", TEST,
                        "-e", "sampleRate", str(rate), "-e", "theme", theme, "-e", "fontScale", scale,
                        "-e", "evidenceRun", case, "-e", "sourceCommit", commit,
                        "-e", "clearPackageData", "false", RUNNER, timeout=180)
                    # Preserve failure properties and PNGs too, before any next sandbox reset.
                    names = shell(case + "-files", "run-as", PACKAGE, "ls", "files/range-hint/" + case).splitlines()
                    for name in names:
                        if name != "result.properties" and not re.fullmatch(r"[a-z-]+\.png", name):
                            raise RuntimeError("unexpected device evidence name")
                        content = run(case + "-" + name, target + ["exec-out", "run-as", PACKAGE,
                            "cat", "files/range-hint/" + case + "/" + name], binary=True)
                        (folder / name).write_bytes(content)
                    record = properties((folder / "result.properties").read_text())
                    if not re.search(r"OK \(1 test\)", output) or "INSTRUMENTATION_CODE: -1" not in output:
                        raise RuntimeError(f"{case} did not report exactly one passing test")
                    required = {"verdict": "PASS", "package": PACKAGE, "sampleRate": str(rate), "theme": theme,
                        "sourceCommit": commit, "apkSha256": hashes["app"], "savedWhilePaused": "0",
                        "sampledDroppedHops": "0", "sampledAnalysisErrors": "0", "historyRejected": "0",
                        "inputStartsBeforeCleanup": "1", "inputStopsBeforeCleanup": "0"}
                    if any(record.get(key) != value for key, value in required.items()):
                        raise RuntimeError(f"{case} has an incomplete or mismatched device verdict")
                    if abs(float(record["actualFontScale"]) - float(scale)) > 0.01:
                        raise RuntimeError(f"{case} used the wrong font scale")
                    for key in ("pausedReadings", "savedAfterCaptureEnabled", "tenorSavedReadings"):
                        if int(record[key]) <= 0:
                            raise RuntimeError(f"{case} is missing actual accepted PCM evidence")
                    results.append(record)
                    (evidence / "cases.json").write_text(json.dumps(results, indent=2) + "\n")
        if snapshot()[0] != digest or subprocess.check_output(
                ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() != commit:
            raise RuntimeError("source changed during device proof")
    finally:
        try:
            if original_font == "null":
                shell("restore-font", "settings", "delete", "system", "font_scale")
            else:
                shell("restore-font", "settings", "put", "system", "font_scale", original_font)
            restored = shell("restored-font", "settings", "get", "system", "font_scale").strip()
            if restored != original_font:
                raise RuntimeError("original emulator font setting was not restored")
        finally:
            shell("final-sandbox-stop", "am", "force-stop", PACKAGE)
    (evidence / "verdict.json").write_text(json.dumps({"verdict": "PASS", "cases": len(results),
        "commit": commit, "snapshotSha256": digest, "apkSha256": hashes,
        "originalFontScale": original_font, "restoredFontScale": restored}, indent=2) + "\n")
    print("PASS: eight deterministic range-hint device cases; font setting restored", flush=True)


if __name__ == "__main__":
    main()
