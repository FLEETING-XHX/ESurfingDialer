#!/usr/bin/env python3
"""在独立目录使用 Docker 替身检查安装器配置保留，不访问真实配置或容器。"""
import argparse
import gzip
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sh", default=shutil.which("sh") or "C:/Program Files/Git/bin/sh.exe")
    parser.add_argument("--report", default="build/installer-regression.json")
    args = parser.parse_args()
    source = Path(__file__).resolve().parent.parent
    root = source / "build" / ("installer-regression-" + uuid.uuid4().hex[:8])
    root.mkdir(parents=True)
    install = (source / "install.sh").read_bytes().replace(b"\r\n", b"\n")
    defaults = {
        b"AUTO_REAUTH_ENABLED": b"1",
        b"AUTO_REAUTH_SAFE_WINDOW_START_HOUR": b"4",
        b"AUTO_REAUTH_SAFE_WINDOW_END_HOUR": b"6",
    }
    custom = dict(zip(defaults, (b"0", b"2", b"3")))
    password = b'DIALER_PASSWORD="fixture$$value\\\\with\\"quote"'
    identity = b'{"fixture":"preserve-device-state"}\n'
    missing_sets = [set(defaults), *({key} for key in defaults), set()]
    cases = []
    checks = 0

    def check(condition, message):
        nonlocal checks
        if not condition:
            raise AssertionError(message)
        checks += 1

    for ending in (b"", b"\n", b"\r\n"):
        for last in ("password", "image"):
            for missing in missing_sets:
                cases.append((ending, last, missing, True))
    cases.append((b"", "password", set(defaults), False))
    outcomes = []
    for index, (ending, last, missing, include_image) in enumerate(cases):
        fixture = root / str(index)
        fixture.mkdir()
        (fixture / "bin").mkdir()
        (fixture / "data").mkdir()
        (fixture / "images").mkdir()
        (fixture / "install.sh").write_bytes(install)
        (fixture / "images/esurfing-dialer-v1.3-amd64.tar.gz").write_bytes(gzip.compress(b"synthetic image only"))
        (fixture / "data/device-state.json").write_bytes(identity)
        commands = {
            "docker": '#!/bin/sh\nprintf "%s\\n" "$*" >> "$INSTALLER_CALLS"\n[ "${1:-}" != load ] || cat >/dev/null\nexit 0\n',
            "uname": "#!/bin/sh\necho x86_64\n",
            "df": "#!/bin/sh\nprintf 'Filesystem 1024-blocks Used Available Capacity Mounted\\nfixture 4000000 0 4000000 0%% /\\n'\n",
        }
        for name, text in commands.items():
            path = fixture / "bin" / name
            path.write_text(text, encoding="utf-8", newline="\n")
            path.chmod(0o755)
        lines = [b'DIALER_USER="fixture-user"', b'OTHER_SETTING="keep-this-value"']
        lines.extend(key + b"=" + value for key, value in custom.items() if key not in missing)
        tail = [password]
        if include_image:
            tail.append(b"DIALER_IMAGE=fixture-old-image")
        if last == "password":
            tail.reverse()
        lines.extend(tail)
        separator = b"\r\n" if ending == b"\r\n" else b"\n"
        (fixture / ".env").write_bytes(separator.join(lines) + ending)
        calls = fixture / "docker-calls.txt"
        # 将Docker替身置于PATH最前，安装器的Docker调用全部由夹具处理。
        command = 'PATH="$PWD/bin:/usr/bin:/bin"; export PATH; sh ./install.sh'
        env = os.environ | {"INSTALLER_CALLS": calls.as_posix()}
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        before_second = None
        for run in range(2):
            result = subprocess.run([args.sh, "-c", command], cwd=fixture, env=env,
                input=b"", capture_output=True, timeout=15, creationflags=flags)
            check(result.returncode == 0, f"case {index}, run {run}: installer failed")
            after = (fixture / ".env").read_bytes()
            records = after.splitlines()
            check(records.count(password) == 1, f"case {index}, run {run}: password changed or merged")
            for preserved in lines[:2]:
                check(records.count(preserved) == 1, f"case {index}, run {run}: existing setting changed")
            check(records.count(b"DIALER_IMAGE=esurfing-dialer:v1.3") == 1,
                f"case {index}, run {run}: image tag missing or merged")
            for key, value in defaults.items():
                expected = value if key in missing else custom[key]
                check(records.count(key + b"=" + expected) == 1,
                    f"case {index}, run {run}: optional setting changed or merged")
            check((fixture / "data/device-state.json").read_bytes() == identity,
                f"case {index}, run {run}: device identity changed")
            if run == 0:
                before_second = after
            else:
                check(after == before_second, f"case {index}: repeated install changed config")
        check("load" in calls.read_text() and "compose up -d --no-build --pull never" in calls.read_text(),
            f"case {index}: complete install path was not exercised")
        outcomes.append({"Case": index, "FinalNewline": ending.decode().replace("\r", "CR").replace("\n", "LF") or "none",
            "LastSetting": last, "MissingKeys": len(missing), "ImagePresentInitially": include_image, "Passed": True})
    report = Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({"Checks": checks, "Cases": outcomes, "Fixture": root.as_posix(),
        "RealDockerUsed": False, "RealConfigurationUsed": False}, indent=2), encoding="utf-8")
    print(f"Docker installer regression: {checks} checks passed across {len(cases)} isolated cases")


if __name__ == "__main__":
    main()
