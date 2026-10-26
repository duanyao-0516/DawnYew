#!/usr/bin/env python3
"""Run production Android OCR on local labeled screenshots without launching the bookkeeping app.

Only the host reads filenames as labels. Images sent to the standalone test APK are anonymous.
Private images/full OCR reports stay under .local and are removed from the device after the run.
"""
from __future__ import annotations

import argparse
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.dawnyew.platform.test"
TEST = "com.dawnyew.platform.ocr.ScreenshotAmountRecognitionTest"
LABEL = re.compile(r"[+-]?(?:0|[1-9][0-9]*)\.[0-9]{2}\Z")


def collect_samples(directory: Path) -> list[dict]:
    files = sorted(p for p in directory.iterdir() if p.is_file() and p.suffix.lower() in {".png", ".jpg", ".jpeg"})
    if not 1 <= len(files) <= 32:
        raise ValueError("Expected 1–32 PNG/JPEG files; each stem must be a signed amount with two decimal places")
    result = []
    for index, file in enumerate(files, 1):
        if not LABEL.fullmatch(file.stem):
            raise ValueError(f"Invalid amount filename: {file.name}")
        if not 0 < file.stat().st_size <= 20 * 1024 * 1024:
            raise ValueError(f"Sample must contain 1 byte–20 MiB: {file.name}")
        minor = int(Decimal(file.stem) * 100)
        if abs(minor) > 2**63 - 1:
            raise ValueError(f"Amount exceeds signed minor-unit range: {file.name}")
        result.append({"id": f"case-{index:03d}", "source": file, "expectedSignedMinor": minor,
                       "expectedDirection": "INCOME" if file.stem.startswith("+") else "EXPENSE",
                       "sha256": hashlib.sha256(file.read_bytes()).hexdigest()})
    return result


def sdk_root() -> Path:
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            return Path(os.environ[key]).expanduser()
    properties = ROOT / "local.properties"
    if properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.removeprefix("sdk.dir=").replace("\\:", ":").replace("\\\\", "\\"))
    return Path.home() / "Library/Android/sdk"


def write_json(file: Path, value: object) -> None:
    file.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--serial", help="ADB device/emulator serial; required if more than one is connected")
    parser.add_argument("--repeats", type=int, choices=range(1, 6), default=3)
    parser.add_argument("--skip-build", action="store_true", help="Use the existing platform test APK (reported as not rebuilt)")
    args = parser.parse_args()
    samples = collect_samples(args.input_dir.expanduser().resolve())
    adb = sdk_root() / "platform-tools/adb"
    if not adb.is_file():
        raise ValueError("ADB unavailable; set ANDROID_HOME or sdk.dir in local.properties")
    devices = subprocess.check_output([str(adb), "devices"], text=True)
    available = [line.split()[0] for line in devices.splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == "device"]
    serial = args.serial or (available[0] if len(available) == 1 else None)
    if serial not in available:
        raise ValueError("Select a connected authorized device using --serial; no device is selected implicitly when multiple exist")
    run_id = uuid.uuid4().hex
    output = ROOT / ".local/ocr-amount-tests" / run_id
    output.mkdir(parents=True, mode=0o700)
    os.chmod(output, 0o700)
    private = output / "samples"
    private.mkdir(mode=0o700)
    manifest = {"repeats": args.repeats, "samples": [{"id": s["id"], "sha256": s["sha256"]} for s in samples]}
    expected = {s["id"]: {"signedMinor": s["expectedSignedMinor"], "direction": s["expectedDirection"]} for s in samples}
    write_json(private / "inputs.json", manifest)
    write_json(private / "expected.json", expected)
    write_json(output / "labels.json", [{"id": s["id"], "filename": s["source"].name,
        "expectedSignedMinor": s["expectedSignedMinor"], "expectedDirection": s["expectedDirection"], "sha256": s["sha256"]} for s in samples])
    for sample in samples:
        shutil.copyfile(sample["source"], private / f"{sample['id']}.image")
    print(f"Private report directory: {output}", flush=True)
    if not args.skip_build:
        with (output / "build.log").open("w") as log:
            built = subprocess.run([str(ROOT / "dev.sh"), "gradle", ":platform:android:assembleDebugAndroidTest", "--offline", "--console=plain"],
                                   cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        if built.returncode:
            print(f"Build failed; inspect {output / 'build.log'}", file=sys.stderr)
            return built.returncode
    apk = ROOT / "platform/android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk"
    if not apk.is_file():
        raise ValueError("Expected standalone platform AndroidTest APK is missing")
    write_json(output / "run.json", {"serial": serial, "runId": run_id, "rebuilt": not args.skip_build,
        "testApkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "sourceHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()})
    base = [str(adb), "-s", serial]
    with (output / "install.log").open("w") as log:
        installed = subprocess.run(base + ["install", "-r", str(apk)], stdout=log, stderr=subprocess.STDOUT, timeout=120)
    if installed.returncode:
        raise RuntimeError(f"Standalone test APK install failed; inspect {output / 'install.log'}")
    remote = f"files/ocr-amount-tests/{run_id}"
    subprocess.run(base + ["shell", "run-as", PACKAGE, "mkdir", "-p", remote], check=True)
    try:
        for file in private.iterdir():
            # Generated paths contain only fixed characters/UUIDs, and are still shell-quoted.
            command = shlex.quote(f"cat > {shlex.quote(remote + '/' + file.name)}")
            with file.open("rb") as stream:
                subprocess.run(base + ["shell", "run-as", PACKAGE, "sh", "-c", command], stdin=stream, check=True)
        with (output / "instrumentation.log").open("w") as log:
            result = subprocess.run(base + ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST,
                "-e", "ocrRunId", run_id, f"{PACKAGE}/androidx.test.runner.AndroidJUnitRunner"],
                stdout=log, stderr=subprocess.STDOUT, timeout=600)
        with (output / "report.json").open("wb") as report:
            pulled = subprocess.run(base + ["exec-out", "run-as", PACKAGE, "cat", f"{remote}/report.json"],
                                    stdout=report, stderr=subprocess.PIPE)
        if pulled.returncode:
            raise RuntimeError(f"No recognition report. Inspect {output / 'instrumentation.log'}")
        report = json.loads((output / "report.json").read_text())
        summary = report.get("summary")
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        logs = (output / "instrumentation.log").read_text()
        return 0 if result.returncode == 0 and "OK (1 test)" in logs and summary and summary["exactSignedTokenMatches"] == summary["runs"] else 1
    finally:
        # Killing a host adb client on timeout does not stop native inference on the device.
        # Stop only this standalone test process before removing its run-owned inputs/results.
        active_error = sys.exc_info()[0] is not None
        cleanup_errors = []
        for command in (["shell", "am", "force-stop", PACKAGE], ["shell", "run-as", PACKAGE, "rm", "-rf", remote]):
            try:
                cleaned = subprocess.run(base + command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
                if cleaned.returncode:
                    cleanup_errors.append(" ".join(command[:3]))
            except subprocess.SubprocessError:
                cleanup_errors.append(" ".join(command[:3]))
        if cleanup_errors:
            message = "Device test cleanup incomplete; private run path: " + remote + "; steps: " + ", ".join(cleanup_errors)
            if active_error:
                print(message, file=sys.stderr)
            else:
                raise RuntimeError(message)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
    except (ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
