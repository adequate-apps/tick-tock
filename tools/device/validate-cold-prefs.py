#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prove cold preference restoration in an installed, disposable emulator package.

Build/install separately with the existing applicationIdOverride property set to
com.adequateapps.metronome.coldproof. This script never builds or installs. It
clears only that dedicated sandbox, runs two instrumentation invocations with a
force-stop between them, and preserves APKs, phase logs and the PID/nonce witness.
Owner package data is never read, cleared, stopped or uninstalled. Sandbox data
remains after the run for inspection; the next run resets only that sandbox.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.adequateapps.metronome.coldproof.debug"
TEST_PACKAGE = PACKAGE + ".test"
RUNNER = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
TEST = "ticktock.app.ColdPreferencesDeviceTest#coldPreferencesPhase"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_inventory():
    names = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT
    ).split(b"\0")
    files = []
    for name in sorted(set(names) - {b""}):
        path = ROOT / os.fsdecode(name)
        files.append({"path": os.fsdecode(name), "sha256": sha256(path) if path.is_file() else "missing"})
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest(), files


def properties(path):
    result = {}
    for line in path.read_text().splitlines():
        if line and not line.startswith(("#", "!")):
            key, value = line.split("=", 1)
            result[key] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit emulator serial")
    parser.add_argument("--app-apk", type=Path, required=True, help="Exact separately built sandbox app APK")
    parser.add_argument("--test-apk", type=Path, required=True, help="Exact separately built sandbox instrumentation APK")
    args = parser.parse_args()
    app_apk, test_apk = args.app_apk.resolve(), args.test_apk.resolve()
    if not app_apk.is_file() or not test_apk.is_file():
        raise RuntimeError("both expected APK files are required")
    adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
    target = [adb, "-s", args.serial]
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    evidence = ROOT / "build/cold-prefs-validation" / stamp
    evidence.mkdir(parents=True)
    print(f"Evidence: {evidence}", flush=True)

    def run(name, command, timeout=120):
        result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=timeout)
        (evidence / (name + ".log")).write_bytes(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError(f"{name} failed; see {evidence / (name + '.log')}")
        return result.stdout.decode("utf-8", errors="replace")

    def shell(name, *arguments, timeout=120):
        return run(name, target + ["shell", *arguments], timeout)

    if run("device-state", target + ["get-state"]).strip() != "device":
        raise RuntimeError("selected emulator is not ready")
    if shell("emulator-check", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("cold persistence proof only permits an emulator")
    shell("device-properties", "getprop")
    installed = shell("instrumentation", "pm", "list", "instrumentation")
    required = f"instrumentation:{RUNNER} (target={PACKAGE})"
    if required not in installed.splitlines():
        raise RuntimeError("expected sandbox instrumentation target is not installed")

    # Verify both installed artifacts before any stop/clear operation. No caller
    # can choose a different target package through command-line parameters.
    apk_hashes = {}
    for label, package, expected in (("app", PACKAGE, app_apk), ("test", TEST_PACKAGE, test_apk)):
        metadata = shell(label + "-package", "dumpsys", "package", package)
        if f"Package [{package}]" not in metadata:
            raise RuntimeError(f"installed {label} package identity was not confirmed")
        paths = shell(label + "-paths", "pm", "path", package).splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:/data/app/"):
            raise RuntimeError(f"unsupported installed {label} APK layout")
        installed_apk = evidence / (label + "-installed.apk")
        run(label + "-pull", target + ["pull", paths[0].removeprefix("package:"), str(installed_apk)])
        if sha256(installed_apk) != sha256(expected):
            raise RuntimeError(f"installed {label} APK differs from the expected build artifact")
        apk_hashes[package] = sha256(installed_apk)

    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    digest, files = source_inventory()
    nonce = secrets.token_hex(16)
    (evidence / "source.json").write_text(json.dumps({
        "commit": commit, "snapshotSha256": digest, "files": files,
        "installedApkSha256": apk_hashes, "serial": args.serial, "nonce": nonce,
    }, indent=2) + "\n")
    (evidence / "working.patch").write_bytes(subprocess.check_output(["git", "diff", "HEAD", "--binary"], cwd=ROOT))

    shell("initial-stop", "am", "force-stop", PACKAGE)
    if shell("sandbox-reset", "pm", "clear", PACKAGE).strip() != "Success":
        raise RuntimeError("dedicated sandbox reset failed")
    passed = []
    for phase in ("prepare", "restore"):
        output = shell(phase, "am", "instrument", "-w", "-r", "-e", "class", TEST,
                       "-e", "coldPhase", phase, "-e", "coldNonce", nonce,
                       "-e", "clearPackageData", "false", RUNNER, timeout=180)
        if not re.search(r"OK \(1 test\)", output) or "INSTRUMENTATION_CODE: -1" not in output:
            raise RuntimeError(f"{phase} did not report exactly one passing test")
        passed.append(phase)
        name = "witness.properties" if phase == "prepare" else "restore.properties"
        result = run(phase + "-evidence", target + ["exec-out", "run-as", PACKAGE,
                     "cat", "files/cold-prefs/" + name])
        (evidence / name).write_text(result)
        if phase == "prepare":
            shell("between-phases-stop", "am", "force-stop", PACKAGE)
            stopped = subprocess.run(target + ["shell", "pidof", PACKAGE], capture_output=True, timeout=30)
            (evidence / "between-phases-pid.log").write_bytes(stopped.stdout + stopped.stderr)
            if stopped.returncode not in (0, 1) or stopped.stdout.strip():
                raise RuntimeError("sandbox process remains after force-stop")

    before, after = properties(evidence / "witness.properties"), properties(evidence / "restore.properties")
    if before.get("nonce") != nonce or after.get("nonce") != nonce:
        raise RuntimeError("phase evidence has the wrong nonce")
    if before.get("pid") == after.get("pid") or after.get("previousPid") != before.get("pid"):
        raise RuntimeError("phase evidence does not prove separate process identities")
    if after.get("prefsSha256") != before.get("prefsSha256") or after.get("verdict") != "PASS":
        raise RuntimeError("cold restoration witness is inconsistent")
    if after.get("savedReadings") != "0" or int(after.get("pausedReadings", "0")) <= 0:
        raise RuntimeError("paused capture lacks actual valid PCM readings skipped while paused")
    if after.get("earsFirstHidden") != "true" or after.get("droneCommandMidi") != "82":
        raise RuntimeError("cold live behavior evidence is incomplete")
    if source_inventory()[0] != digest or subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
    ).strip() != commit:
        raise RuntimeError("source changed during validation")
    verdict = {"verdict": "PASS", "instrumentationTests": len(passed), "phases": passed,
               "preparePid": before["pid"], "restorePid": after["pid"], "nonce": nonce,
               "criteria": ["CAP-02", "EAR-03", "TON-06"], "installedApkSha256": apk_hashes}
    (evidence / "verdict.json").write_text(json.dumps(verdict, indent=2) + "\n")
    print(json.dumps(verdict, indent=2), flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as failure:
        print(f"cold-prefs-validation: FAIL: {failure}", file=sys.stderr)
        sys.exit(1)
