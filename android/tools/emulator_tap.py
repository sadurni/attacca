"""Tap an on-screen element of the emulator by its text or content-desc.

Usage: python emulator_tap.py "<text>" [--long]   |   python emulator_tap.py --list
Set ANDROID_SERIAL to choose the device when more than one is connected.
"""
import os
import re
import shutil
import subprocess
import sys
import time

# adb from the Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT), the default SDK location, or PATH.
_sdk = (
    os.environ.get("ANDROID_HOME")
    or os.environ.get("ANDROID_SDK_ROOT")
    or os.path.join(os.path.expanduser("~"), "AppData", "Local", "Android", "Sdk")
)
_adb = os.path.join(_sdk, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
_local = os.path.join(os.path.expanduser("~"), "Android", "Sdk", "platform-tools", "adb.exe" if os.name == "nt" else "adb")
ADB = next((p for p in (_adb, _local) if os.path.exists(p)), shutil.which("adb") or "adb")


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8").stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    for m in re.finditer(r'<node [^>]*>', xml):
        node = m.group(0)
        text = re.search(r' text="([^"]*)"', node).group(1)
        desc = re.search(r'content-desc="([^"]*)"', node).group(1)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        x1, y1, x2, y2 = map(int, b.groups())
        yield text, desc, ((x1 + x2) // 2, (y1 + y2) // 2)


def main():
    if sys.argv[1] == "--list":
        for text, desc, center in nodes():
            if text or desc:
                print(f"{text!r} {desc!r} {center}")
        return
    target = sys.argv[1]
    for text, desc, (x, y) in nodes():
        if target in (text, desc):
            if "--long" in sys.argv:
                adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "800")
            else:
                adb("shell", "input", "tap", str(x), str(y))
            time.sleep(1)
            print(f"tapped {target!r} at {x},{y}")
            return
    print(f"NOT FOUND: {target!r}")
    sys.exit(1)


if __name__ == "__main__":
    main()
