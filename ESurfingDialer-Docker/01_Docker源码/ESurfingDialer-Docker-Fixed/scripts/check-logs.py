#!/usr/bin/env python3
"""Run quota/rotation regressions using only an isolated temporary log directory."""
import argparse
import datetime
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sh", default=shutil.which("sh") or "C:/Program Files/Git/bin/sh.exe")
    args = parser.parse_args()
    source = Path(__file__).resolve().parent.parent
    with tempfile.TemporaryDirectory(prefix="esurfing-log-test-") as folder:
        root = Path(folder); logs = root / "logs"; logs.mkdir()
        for name in ["log-maintenance.sh", "log-sink.awk"]:
            shutil.copyfile(source / "docker" / name, root / name)
        unrelated = logs / "keep-this-user-file.txt"
        unrelated.write_text("user data")
        (logs / "dialer-2000-01-01.log").write_bytes(b"old log" * 2000)
        env = os.environ | {"LOG_DIR": logs.as_posix(), "LOG_FILE_MAX_BYTES": "1024", "LOG_TOTAL_MAX_BYTES": "3072", "LOG_ROTATED_FILES": "2", "TZ": "UTC", "LC_ALL": "C"}
        command = 'awk -v helper="$(pwd)/log-maintenance.sh" -f log-sink.awk'
        records = "".join(f"record-{i:03d} 中文 " + "x" * 70 + "\n" for i in range(200))
        result = subprocess.run([args.sh, "-c", command], cwd=root, env=env,
            input=records.encode(), capture_output=True, timeout=20)
        assert result.returncode == 0, result.stderr
        assert result.stdout.decode() == records
        files = list(logs.glob("dialer-*.log*"))
        assert all(f.stat().st_size <= 1024 for f in files)
        assert sum(f.stat().st_size for f in files) <= 3072
        assert len(files) <= 3
        current = logs / ("dialer-" + datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d") + ".log")
        assert "record-199" in current.read_text(encoding="utf-8")
        assert unrelated.read_text() == "user data"
        result = subprocess.run([args.sh, "-c", command], cwd=root, env=env,
            input=b"x" * 5000 + b"\n", capture_output=True, timeout=20)
        assert result.returncode == 0 and b"[log line truncated]" in result.stdout
        assert len(result.stdout) <= 1024
        assert sum(f.stat().st_size for f in logs.glob("dialer-*.log*")) <= 3072
        # Existing oversized files are bounded on maintenance; unrelated names are untouched.
        old = logs / "dialer-1999-12-31.log"
        old.write_bytes(b"y" * 4000)
        result = subprocess.run([args.sh, "log-maintenance.sh", "maintain", "2026-09-26"], cwd=root, env=env, capture_output=True, timeout=20)
        assert result.returncode == 0, result.stderr
        assert all(f.stat().st_size <= 1024 for f in logs.glob("dialer-*.log*"))
        assert sum(f.stat().st_size for f in logs.glob("dialer-*.log*")) <= 3072
        assert unrelated.read_text() == "user data"
        before = {f.name: f.read_bytes() for f in logs.iterdir()}
        result = subprocess.run([args.sh, "log-maintenance.sh", "rotate", "../../wrong"], cwd=root, env=env, capture_output=True, timeout=10)
        assert result.returncode != 0
        assert before == {f.name: f.read_bytes() for f in logs.iterdir()}
        result = subprocess.run([args.sh, "log-maintenance.sh", "maintain", "2026-09-26"], cwd=root,
            env=env | {"LOG_TOTAL_MAX_BYTES": "1"}, capture_output=True, timeout=10)
        assert result.returncode != 0
    # Use the actual FIFO startup sequence with a delayed fake Java writer.
    with tempfile.TemporaryDirectory(prefix="esurfing-entrypoint-test-") as folder:
        root = Path(folder)
        for name in ["entrypoint.sh", "log-maintenance.sh", "log-sink.awk"]:
            shutil.copyfile(source / "docker" / name, root / name)
        (root / "bin").mkdir()
        fake_java = root / "bin/java"
        fake_java.write_text("#!/bin/sh\nsleep 0.2\necho JAVA_FIXTURE_STARTED\nsleep 0.2\necho JAVA_FIXTURE_LAST\nexit 7\n", encoding="utf-8", newline="\n")
        fake_java.chmod(0o755)
        result = subprocess.run([args.sh, "entrypoint.sh"], cwd=root,
            env=os.environ | {"PATH": (root / "bin").as_posix() + os.pathsep + os.environ["PATH"],
                "STATE_DIR": (root / "state").as_posix(), "DIALER_USER": "fixture", "DIALER_PASSWORD": "fixture"},
            capture_output=True, timeout=20)
        assert result.returncode == 7, (result.returncode, result.stderr)
        assert b"BACKEND_SELECTED" in result.stdout and b"JAVA_FIXTURE_LAST" in result.stdout, result.stdout
        logged = b"".join(f.read_bytes() for f in (root / "state/logs").glob("dialer-*.log"))
        assert b"JAVA_FIXTURE_STARTED" in logged and b"JAVA_FIXTURE_LAST" in logged
    print("Docker log regression: 18 checks passed")


if __name__ == "__main__":
    main()
