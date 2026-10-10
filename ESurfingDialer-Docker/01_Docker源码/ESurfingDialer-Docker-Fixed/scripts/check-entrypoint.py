#!/usr/bin/env python3
"""使用生产入口、真实 awk 和隔离的 Java/su-exec 替身验证参数与退出监督。"""
import argparse
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import time
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sh", default=shutil.which("sh") or "C:/Program Files/Git/bin/sh.exe")
    parser.add_argument("--report", default="build/entrypoint-regression.json")
    parser.add_argument("--entrypoint", type=Path)
    parser.add_argument("--only", choices=("all", "logger-failure", "logger-hang"), default="all")
    args = parser.parse_args()
    source = Path(__file__).resolve().parent.parent
    root = source / "build" / ("entrypoint-regression-" + uuid.uuid4().hex[:8])
    root.mkdir(parents=True)
    checks = 0
    cases = []
    cli = []

    def verify(condition, label):
        nonlocal checks
        if not condition:
            raise AssertionError(label)
        checks += 1

    def write(path, text):
        path.write_text(text, encoding="utf-8", newline="\n")
        path.chmod(0o755)

    def run(name, branch="fallback", password="fixture", backend="unicorn2", failure="", stubborn=False, terminate=False):
        fixture = root / name
        fixture.mkdir()
        (fixture / "bin").mkdir()
        for filename in ("entrypoint.sh", "log-maintenance.sh", "log-sink.awk"):
            original = args.entrypoint if filename == "entrypoint.sh" and args.entrypoint else source / "docker" / filename
            write(fixture / filename, original.read_text(encoding="utf-8"))
        write(fixture / "bin/id", "#!/bin/sh\nexit " + ("0" if branch == "su-exec" else "1") + "\n")
        write(fixture / "bin/su-exec", "#!/bin/sh\nprintf selected > su-exec-selected\nshift\nexec \"$@\"\n")
        if failure in ("maintain", "rotate"):
            (fixture / "log-maintenance.sh").rename(fixture / "real-maintenance.sh")
            write(fixture / "log-maintenance.sh", """#!/bin/sh
count=0
[ ! -f helper-count ] || read -r count < helper-count
count=$((count + 1))
printf '%s\n' "$count" > helper-count
if [ "$1" = "$FAILURE" ] && [ "$count" -gt 1 ]; then exit 46; fi
exec sh ./real-maintenance.sh "$@"
""")
        if failure == "early":
            write(fixture / "bin/awk", "#!/bin/sh\nexit 0\n")
        if failure == "hang":
            write(fixture / "bin/awk", "#!/bin/sh\nprintf '%s\\n' \"$$\" > sink-pid\ntrap '' TERM\nwhile :; do :; done\n")
        if (not failure or failure == "hang") and not terminate:
            write(fixture / "java-fixture.py", """import json, sys, time
from pathlib import Path
Path('argv.json').write_text(json.dumps(sys.argv[1:]), encoding='utf-8')
time.sleep(.2)
print('JAVA_FIXTURE_STARTED', flush=True)
time.sleep(.2)
print('JAVA_FIXTURE_LAST', flush=True)
sys.exit(7)
""")
            java = "#!/bin/sh\nexec " + shlex.quote(Path(sys.executable).as_posix()) + " java-fixture.py \"$@\"\n"
        else:
            java = "#!/bin/sh\nprintf '%s\\n' \"$$\" > java-pid\n"
            if stubborn:
                java += "trap '' TERM PIPE\n"
            # 全部输出由当前替身进程生成，不留下 sleep 等孙进程。
            java += "sleep 0.2\n"
            java += "while :; do printf '%0512d\\n' 0 2>/dev/null; done\n" if failure and failure != "early" else "while :; do :; done\n"
        write(fixture / "bin/java", java)
        launcher = "#!/bin/sh\nexport PATH=\"$PWD/bin:$PATH\"\n"
        if terminate:
            launcher += """sh entrypoint.sh &
target=$!
attempt=0
while [ ! -f java-pid ] && [ "$attempt" -lt 50 ]; do sleep .1; attempt=$((attempt + 1)); done
kill -TERM "$target"
wait "$target"
"""
        else:
            launcher += "exec sh entrypoint.sh\n"
        write(fixture / "launch.sh", launcher)
        env = os.environ | {"STATE_DIR": "state", "LOG_DIR": "state/logs", "DIALER_USER": '-u"fixture user"',
            "DIALER_PASSWORD": password, "DIALER_BACKEND": backend, "FAILURE": failure,
            "LOG_FILE_MAX_BYTES": "1024", "LOG_TOTAL_MAX_BYTES": "3072", "JAVA_OPTS": ""}
        start = time.monotonic()
        process = subprocess.Popen([args.sh, "launch.sh"], cwd=fixture, env=env,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=os.name != "nt")
        try:
            stdout, stderr = process.communicate(timeout=18)
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True, check=False)
            else:
                import signal
                os.killpg(process.pid, signal.SIGKILL)
            process.communicate(timeout=5)
            raise AssertionError(name + ": entrypoint did not finish within 18 seconds")
        elapsed = time.monotonic() - start
        if failure or terminate:
            expected = 143 if terminate else (7 if failure == "hang" else 1)
            verify(process.returncode == expected, name + ": failure exit code " + str(process.returncode))
            verify(elapsed < 16, name + ": bounded shutdown")
            verify(b"log process" in stderr or terminate, name + ": failure diagnostic")
            for pid_file in (fixture / "java-pid", fixture / "sink-pid"):
                if not pid_file.exists():
                    continue
                # PID 来自本用例私有目录，只检查我们启动的替身。
                pid = pid_file.read_text().strip()
                alive = subprocess.run([args.sh, "-c", 'kill -0 "$1" 2>/dev/null', "probe", pid], capture_output=True)
                verify(alive.returncode != 0, name + ": owned child was reaped (" + pid_file.name + ")")
        else:
            verify(process.returncode == 7, name + ": Java exit code preserved")
            verify(b"BACKEND_SELECTED" in stdout and b"JAVA_FIXTURE_LAST" in stdout, name + ": stdout drained")
            logged = b"".join(f.read_bytes() for f in (fixture / "state/logs").glob("dialer-*.log*"))
            verify(b"JAVA_FIXTURE_STARTED" in logged and b"JAVA_FIXTURE_LAST" in logged, name + ": final log drained")
            argv = json.loads((fixture / "argv.json").read_text(encoding="utf-8"))
            verify('--user=' + env["DIALER_USER"] in argv and '--password=' + password in argv, name + ": credentials preserved as single args")
            verify(("-d" in argv) == (backend == "dynarmic"), name + ": backend option isolated from password")
            verify((fixture / "su-exec-selected").exists() == (branch == "su-exec"), name + ": requested launch branch exercised")
            cli.append({"argv": argv[argv.index("-jar") + 2:], "user": env["DIALER_USER"], "password": password, "dynarmic": backend == "dynarmic"})
        cases.append({"name": name, "seconds": round(elapsed, 3), "exitCode": process.returncode})

    if args.only == "logger-failure":
        run("logger-fallback-maintain", failure="maintain")
    if args.only == "logger-hang":
        for branch in ("fallback", "su-exec"):
            run("logger-" + branch + "-hang", branch=branch, failure="hang")
    for branch in (() if args.only != "all" else ("fallback", "su-exec")):
        for index, password in enumerate(("-d", "--dynarmic", '"quoted fixture"', "spaces $$ # \\ 中文=1")):
            for backend in ("unicorn2", "dynarmic"):
                run(f"cli-{branch}-{index}-{backend}", branch=branch, password=password, backend=backend)
        for failure in ("maintain", "rotate", "early"):
            run(f"logger-{branch}-{failure}", branch=branch, failure=failure)
        run("logger-" + branch + "-stubborn", branch=branch, failure="maintain", stubborn=True)
        run("logger-" + branch + "-hang", branch=branch, failure="hang")
        run("terminate-" + branch, branch=branch, stubborn=True, terminate=True)
    report = Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({"checks": checks, "cases": cases, "cli": cli}, ensure_ascii=False, indent=2), encoding="utf-8")
    # 使用已有平面状态 JSON 解析器，让 JVM 回归消费实际捕获的参数。
    flat_cases = []
    for case in cli:
        flat_cases.append({key: value for key, value in case.items() if key != "argv"} |
            {"argvCount": len(case["argv"])} | {"argv" + str(index): value for index, value in enumerate(case["argv"])})
    report.with_suffix(".cli.jsonl").write_text("\n".join(json.dumps(case, ensure_ascii=False) for case in flat_cases) + "\n", encoding="utf-8")
    print(f"Docker entrypoint regression: {checks} checks / {len(cases)} cases passed")


if __name__ == "__main__":
    main()
