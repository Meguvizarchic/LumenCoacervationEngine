"""Run lumen-engine's own instrumentation APK class by class with a hard per-class timeout.

The library test APK has no Activity. Vendor power managers (MIUI SmartPower, for example) may idle such a
windowless process, so a whole-package run can hang forever. Each class therefore runs alone under a timeout,
and a class only passes when the raw output carries an ``OK (n tests)`` line and exactly one successful
``INSTRUMENTATION_CODE: -1``. adb's own exit status is never trusted.

Usage: python tools/engine_device_tests.py --apk <lumen-engine-debug-androidTest.apk> --output <dir>
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

PACKAGE = "com.lumen.coacervation.engine.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
TEST_PACKAGE = "com.lumen.coacervation.engine"
SOURCE_DIR = Path(__file__).resolve().parents[1] / "lumen-engine/src/androidTest/java/com/lumen/coacervation/engine"


def discover(source_dir: Path = SOURCE_DIR) -> list[str]:
    """Instrumented classes, in name order: files declaring a class annotated with @RunWith."""
    names = []
    for path in sorted(source_dir.glob("*.kt")):
        text = path.read_text(encoding="utf-8")
        match = re.search(r"@RunWith\([^)]*\)\s*(?:@\w+(?:\([^)]*\))?\s*)*class\s+(\w+)", text)
        if match:
            names.append(match.group(1))
    return names


def classify(output: str, timed_out: bool) -> tuple[str, int]:
    """Return (status, tests): PASSED, FAILED, TIMEOUT or INCOMPLETE."""
    if timed_out:
        return "TIMEOUT", 0
    codes = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", output, re.M)
    ok = re.findall(r"^OK \((\d+) tests?\)\s*$", output, re.M)
    if re.search(r"^FAILURES!!!\s*$", output, re.M):
        return "FAILED", 0
    if codes == ["-1"] and len(ok) == 1 and int(ok[0]) > 0:
        return "PASSED", int(ok[0])
    return "INCOMPLETE", 0


def default_adb() -> str:
    root = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    candidate = Path(root) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    return str(candidate) if candidate.is_file() else "adb"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--adb", default=default_adb())
    parser.add_argument("--timeout", type=float, default=300.0, help="seconds per class")
    parser.add_argument("--classes", default="", help="comma-separated simple class names; default: all")
    parser.add_argument("--serial", default="", help="adb device serial when several devices are attached")
    args = parser.parse_args(argv)
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    classes = [name for name in args.classes.split(",") if name] or discover()
    install = subprocess.run(adb + ["install", "-r", "-t", args.apk], capture_output=True, text=True)
    if install.returncode != 0 or "Success" not in install.stdout:
        print(f"install failed: {install.stdout.strip()} {install.stderr.strip()}")
        return 2
    results = []
    for name in classes:
        subprocess.run(adb + ["shell", "am", "force-stop", PACKAGE], capture_output=True)
        started = time.monotonic()
        command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", f"{TEST_PACKAGE}.{name}",
                   f"{PACKAGE}/{RUNNER}"]
        try:
            run = subprocess.run(command, capture_output=True, text=True, timeout=args.timeout)
            text, timed_out = run.stdout + run.stderr, False
        except subprocess.TimeoutExpired as expired:
            text = (expired.stdout or b"").decode("utf-8", "replace") if isinstance(expired.stdout, bytes) else (expired.stdout or "")
            timed_out = True
            subprocess.run(adb + ["shell", "am", "force-stop", PACKAGE], capture_output=True)
        (output / f"{name}.txt").write_text(text, encoding="utf-8")
        status, tests = classify(text, timed_out)
        results.append({"class": name, "status": status, "tests": tests,
                        "seconds": round(time.monotonic() - started, 1)})
        print(f"{status:10} {name} tests={tests} {results[-1]['seconds']}s", flush=True)
    summary = {"package": PACKAGE, "timeout_seconds": args.timeout, "results": results,
               "passed": all(item["status"] == "PASSED" for item in results)}
    (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    if any(item["status"] == "TIMEOUT" for item in results):
        print("Hint: a windowless test process may be idled by the vendor power manager; "
              "allow unrestricted background activity for " + PACKAGE + ".")
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
