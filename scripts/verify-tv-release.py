#!/usr/bin/env python3
"""Launch an actual release on an isolated Google TV and retain data across updates.

Run under the android,heavy_build resource lock. The test never selects a movie,
starts a stream, logs in, or touches an existing emulator's application data.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = "com.nuvio.tv.shuffle"
IMAGE = "system-images;android-34;google-tv;x86"


class StartupFailure(RuntimeError):
    pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--previous-apk", type=Path)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--port", type=int, default=5562)
    args = parser.parse_args()
    if not args.sdk:
        parser.error("--sdk or ANDROID_HOME is required")
    for apk in (args.apk, args.previous_apk):
        if apk and not apk.is_file():
            parser.error(f"APK does not exist: {apk}")
    args.work_dir.mkdir(parents=True, exist_ok=True)
    fixture = Path(tempfile.mkdtemp(prefix="tv-fixture-", dir=args.work_dir))
    avd_home = fixture / "avds"
    avd_home.mkdir()
    env = {**os.environ, "ANDROID_AVD_HOME": str(avd_home)}
    adb_bin = str(args.sdk / "platform-tools/adb")
    emulator_bin = str(args.sdk / "emulator/emulator")
    serial = f"emulator-{args.port}"

    def run(command, timeout=60, check=True, **kwargs):
        return subprocess.run(command, text=True, capture_output=True, timeout=timeout,
                              check=check, env=env, **kwargs)

    def adb(*command, timeout=60, check=True):
        if process is not None and process.poll() is not None:
            raise RuntimeError(f"Google TV emulator exited with status {process.returncode}")
        return run([adb_bin, "-s", serial, *command], timeout=timeout, check=check)

    def app_alive():
        result = adb("shell", "pidof", PACKAGE, check=False)
        if result.returncode and result.stderr.strip():
            raise RuntimeError("ADB transport failed while checking the app process")
        return bool(result.stdout.strip())

    # Refuse to attach to or terminate any device we did not create.
    devices = run([adb_bin, "devices"]).stdout
    if re.search(rf"^{re.escape(serial)}\s", devices, re.MULTILINE):
        raise RuntimeError(f"Port {args.port} already belongs to another emulator")
    run([str(args.sdk / "cmdline-tools/latest/bin/avdmanager"), "create", "avd",
         "-n", "release-gate", "-k", IMAGE, "-d", "tv_1080p"], input="no\n", timeout=90)
    results = []
    process = None
    try:
        with (fixture / "emulator.log").open("w") as log:
            process = subprocess.Popen(
                [emulator_bin, "-avd", "release-gate", "-port", str(args.port),
                 "-no-window", "-no-audio", "-no-snapshot", "-no-boot-anim",
                 "-gpu", "swiftshader", "-feature", "-Vulkan",
                 "-memory", "1536", "-cores", "2"],
                env=env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"Owned emulator exited; see {fixture / 'emulator.log'}")
                if adb("shell", "getprop", "sys.boot_completed", check=False).stdout.strip() == "1":
                    break
                time.sleep(2)
            else:
                raise RuntimeError("Google TV boot deadline exceeded")
            for namespace, key in (("global", "device_provisioned"),
                                   ("secure", "user_setup_complete"),
                                   ("secure", "tv_user_setup_complete")):
                adb("shell", "settings", "put", namespace, key, "1")
            adb("shell", "wm", "dismiss-keyguard")
            sdk_int = int(adb("shell", "getprop", "ro.build.version.sdk").stdout.strip())

            def crash_evidence(stage):
                logs = adb("logcat", "-b", "crash", "-d", check=False).stdout
                private_log = fixture / f"{stage}-crash.log"
                private_log.touch(mode=0o600)
                private_log.write_text(logs)
                # Show types and stack locations, never exception messages or raw UI.
                for line in logs.splitlines():
                    exception = re.search(r"(?:[a-zA-Z_]\w*\.)+[A-Z]\w*(?:Exception|Error)\b", line)
                    frame = re.search(r"\bat [\w.$]+\([^)]*\)", line)
                    if exception or frame:
                        print((exception or frame).group(0), flush=True)
                print(f"Private diagnostic log: {private_log}", flush=True)

            def verify(apk, stage):
                print(f"Installing {stage}: {apk.name}", flush=True)
                # Match the app updater's PackageInstaller session, including its
                # embedded ART profile. Plain adb install skips that install path.
                staged = fixture / stage
                staged.mkdir()
                shutil.copyfile(apk, staged / "base.apk")
                with zipfile.ZipFile(apk) as archive:
                    index_name = "assets/updater_dm/index.properties"
                    if index_name in archive.namelist():
                        index = {}
                        for line in archive.read(index_name).decode().splitlines():
                            match = re.fullmatch(r"(\d+)\s*=\s*(.+)", line.strip())
                            if match:
                                index[int(match[1])] = match[2]
                        name = index.get(sdk_int)
                        listed = [level for level in index if level != 2147483647]
                        if name is None and listed and sdk_int > max(listed):
                            name = index.get(2147483647)
                        if name:
                            (staged / "base.dm").write_bytes(archive.read("assets/updater_dm/" + name))
                if (staged / "base.dm").exists():
                    adb("install-multiple", "-r", str(staged / "base.apk"),
                        str(staged / "base.dm"), timeout=180)
                else:
                    adb("install", "-r", str(staged / "base.apk"), timeout=180)
                for launch in range(2):
                    adb("shell", "am", "force-stop", PACKAGE)
                    adb("logcat", "-b", "crash", "-c")
                    resolved = adb("shell", "cmd", "package", "resolve-activity", "--brief", PACKAGE).stdout.strip()
                    component = resolved.splitlines()[-1]
                    if not component.startswith(PACKAGE + "/"):
                        raise RuntimeError("Release launcher could not be resolved")
                    started = adb("shell", "am", "start", "-W", "-n", component).stdout
                    if "Status: ok" not in started:
                        raise StartupFailure("Release launcher failed to start")
                    time.sleep(5)
                    home = False
                    choices = ("Continue without account", "Advanced", "Classic View", "Classic",
                               "Profile 1", "Skip for now", "Skip setup", "Continue", "Done", "Skip")
                    for _ in range(20):
                        if not app_alive():
                            crash_evidence(stage)
                            raise StartupFailure(f"{stage} died during startup")
                        adb("shell", "uiautomator", "dump", "/sdcard/nuvio-release-ui.xml", check=False)
                        raw = adb("shell", "cat", "/sdcard/nuvio-release-ui.xml", check=False).stdout
                        try:
                            tree = ET.fromstring(raw)
                        except ET.ParseError:
                            time.sleep(2)
                            continue
                        nodes = [n for n in tree.iter("node") if n.get("package") == PACKAGE]
                        labels = [n.get("text") or n.get("content-desc") or "" for n in nodes]
                        home = (("Home" in labels and any(x in labels for x in ("Search", "Settings", "Discover")))
                                or ("Popular - Movie" in labels and "Popular - Series" in labels))
                        if home:
                            break
                        chosen = next((n for wanted in choices for n, label in zip(nodes, labels)
                                       if label == wanted and n.get("enabled") == "true"), None)
                        if chosen is not None:
                            coords = list(map(int, re.findall(r"\d+", chosen.get("bounds", ""))))
                            if len(coords) != 4:
                                raise RuntimeError("Invalid onboarding button bounds")
                            adb("shell", "input", "tap", str((coords[0] + coords[2]) // 2),
                                str((coords[1] + coords[3]) // 2))
                            adb("shell", "input", "dpad", "keyevent", "KEYCODE_DPAD_CENTER")
                        else:
                            # Open Home's collapsed sidebar without selecting catalogue items.
                            adb("shell", "input", "dpad", "keyevent", "KEYCODE_DPAD_LEFT")
                        time.sleep(3)
                    if not home:
                        raise StartupFailure(f"{stage} did not reach Home")
                    time.sleep(20)
                    alive = app_alive()
                    crashes = adb("logcat", "-b", "crash", "-d").stdout
                    # Keep raw UI/logcat private: account/connection codes must not enter CI logs.
                    if not alive or f"Process: {PACKAGE}," in crashes:
                        crash_evidence(stage)
                        raise StartupFailure(f"{stage} failed the post-Home survival check")
                    results.append({"stage": stage, "cold_launch": launch + 1, "home": True,
                                    "survived": True, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()})
                    print(f"PASS: {stage} cold launch {launch + 1}, Home reached and stable", flush=True)

            if args.previous_apk:
                try:
                    verify(args.previous_apk, "previous")
                except StartupFailure as error:
                    # A broken released version is the reason for a hotfix. Its
                    # failure must not prevent testing the repair over its data.
                    results.append({"stage": "previous", "survived": False, "error": str(error)})
                    print(f"Previous release failed startup: {error}; testing the replacement over its data", flush=True)
            verify(args.apk, "candidate")
    finally:
        (args.work_dir / "tv-startup-results.json").write_text(json.dumps(
            {"image": IMAGE, "checks": results}, indent=2) + "\n")
        if process is not None and process.poll() is None:
            adb("emu", "kill", check=False)
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=15)


if __name__ == "__main__":
    main()
