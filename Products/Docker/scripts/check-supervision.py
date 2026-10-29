#!/usr/bin/env python3
"""Offline shell regressions; all docker/date/sleep/timeout calls are isolated substitutes."""
import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sh", default=shutil.which("sh") or "C:/Program Files/Git/bin/sh.exe")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parent.parent
    checks = 0
    now = 1700000000
    normal = dict(schemaVersion=2, processAlive=True, clientThreadAlive=True,
        networkCheckThreadAlive=True, authenticated=True, lastUpdatedAt=now,
        lastNetworkCheckCompletedAt=now, lastNetworkCheckAt=now,
        lastNetworkCheckSuccessAt=now, lastClientProgressAt=now,
        clientStageDeadlineAt=now + 120, clientStageTimeoutSeconds=120,
        networkCheckIntervalSeconds=5, networkProbeBudgetSeconds=60, healthWriteIntervalSeconds=5,
        lastLoginSuccessAt=now - 30, lastHeartbeatSuccessAt=now,
        lastPortalReauthRequestAt=0)
    results = []
    with tempfile.TemporaryDirectory(prefix="esurfing-supervision-") as folder:
        root = Path(folder)
        for subdir in ["bin", "docker", "scripts", "state"]:
            (root / subdir).mkdir()
        for name in ["health-state.sh", "healthcheck.sh"]:
            shutil.copyfile(source / "docker" / name, root / "docker" / name)
        shutil.copyfile(source / "scripts/openwrt-watchdog.sh", root / "scripts/openwrt-watchdog.sh")
        shutil.copyfile(source / "scripts/measure-runtime.sh", root / "scripts/measure-runtime.sh")
        scripts = {
            "date": '#!/bin/sh\nprintf "%s\\n" "$TEST_NOW"\n',
            "timeout": '#!/bin/sh\nshift\nexec "$@"\n',
            "logger": '#!/bin/sh\nexit 0\n',
            "sleep": '#!/bin/sh\nc="$(cat "$COUNTER_FILE" 2>/dev/null || echo 0)"\nc=$((c+1))\nprintf "%s\\n" "$c" > "$COUNTER_FILE"\n[ "$c" -lt "${LOOPS:-3}" ] || exit 7\n',
            "docker": """#!/bin/sh
printf '%s\n' "$*" >> "$CALLS_FILE"
case "$1" in
 inspect)
   c="$(cat "$INSPECT_COUNTER" 2>/dev/null || echo 0)"
   c=$((c+1)); printf '%s\n' "$c" > "$INSPECT_COUNTER"
   case "${TEST_MODE:-running}" in
    stopped) echo 'false false false unless-stopped' ;;
    missing) exit 1 ;;
    paused) echo 'true true false unless-stopped' ;;
    restarting) echo 'true false true unless-stopped' ;;
    no-policy) echo 'true false false no' ;;
    race) if [ "$c" -ge 2 ]; then echo 'false false false unless-stopped'; else echo 'true false false unless-stopped'; fi ;;
    *) echo 'true false false unless-stopped' ;;
   esac ;;
 exec)
   if [ "$2" = '--user' ]; then printf 'RECYCLE\n' >> "$CALLS_FILE"; elif [ "${TEST_MODE:-}" = conflict ]; then printf '%s\n' '__ESURFING_CORE_STATE_IN_USE__'; else cat "$TEST_HEALTH"; fi ;;
 stats) echo '1.00%,20MiB / 1GiB,8' ;;
 *) echo 'Unexpected docker operation' >&2; exit 99 ;;
esac
""",
        }
        for name, text in scripts.items():
            (root / "bin" / name).write_text(text, encoding="utf-8", newline="\n")
            (root / "bin" / name).chmod(0o755)
        env = os.environ.copy()
        # Forward slashes also work for the Git shell on Windows.
        env.update(TEST_NOW=str(now), STATE_DIR=(root / "state").as_posix(),
            CORE_STATE_CONFLICT_FILE=(root / "conflict-marker").as_posix(),
            TEST_HEALTH=(root / "state/health.json").as_posix(),
            CALLS_FILE=(root / "calls").as_posix(), COUNTER_FILE=(root / "sleeps").as_posix(),
            INSPECT_COUNTER=(root / "inspects").as_posix(),
            CONTAINER_NAME="ESurfingDialer-Beta", STATE_FILE=(root / "cooldown").as_posix(),
            PAUSE_FILE=(root / "paused").as_posix(), LOCK_DIR=(root / "lock").as_posix(),
            FAIL_THRESHOLD="3", CHECK_INTERVAL_SECONDS="1", RESTART_COOLDOWN_SECONDS="300")
        prefix = 'PATH="$(pwd)/bin:/usr/bin:/bin"; export PATH; '

        def run(script, additions=None):
            return subprocess.run([args.sh, "-c", prefix + script], cwd=root,
                env=env | (additions or {}), capture_output=True, text=True, timeout=15)

        def health(label, data, expected):
            nonlocal checks
            (root / "state/health.json").write_text(json.dumps(data, indent=2), encoding="utf-8", newline="\n")
            result = run('sh docker/healthcheck.sh')
            assert (result.returncode == 0) == expected, (label, result.returncode, result.stderr)
            checks += 1; results.append(label)

        health("fresh authenticated", normal, True)
        (root / "conflict-marker").write_text("CORE_STATE_IN_USE")
        health("container conflict cannot borrow another core's valid health", normal, False)
        (root / "conflict-marker").unlink()
        for field in ["lastUpdatedAt", "lastNetworkCheckCompletedAt", "lastClientProgressAt", "lastLoginSuccessAt", "lastHeartbeatSuccessAt", "lastNetworkCheckAt", "lastNetworkCheckSuccessAt", "lastPortalReauthRequestAt"]:
            changed = normal | {field: now + 300}
            health("future " + field, changed, False)
        health("stale reporter", normal | {"lastUpdatedAt": now - 1000}, False)
        health("stale probe progress", normal | {"lastNetworkCheckCompletedAt": now - 1000}, False)
        health("blocked client stage", normal | {"lastClientProgressAt": now - 200, "clientStageDeadlineAt": now - 80}, False)
        health("fabricated stage deadline", normal | {"clientStageDeadlineAt": now + 5000}, False)
        health("unbounded stage timeout", normal | {"clientStageTimeoutSeconds": 9999}, False)
        health("missing thread", normal | {"clientThreadAlive": False}, False)
        health("no authentication", normal | {"authenticated": False}, False)
        unowned = normal | {"authenticated": False, "networkReachable": True, "connectionState": "network-online-unowned", "lastLoginSuccessAt": 0, "lastHeartbeatSuccessAt": 0}
        health("explicit fresh network without owned session is usable", unowned, True)
        health("unowned online needs a fresh successful probe", unowned | {"lastNetworkCheckSuccessAt": now - 1000}, False)
        health("unowned state cannot override failed probe", unowned | {"networkReachable": False}, False)
        health("unowned online cannot hide stalled thread", unowned | {"clientThreadAlive": False}, False)
        health("network flag alone cannot bypass authentication", unowned | {"connectionState": "authenticating"}, False)
        health("fresh login without heartbeat", normal | {"lastHeartbeatSuccessAt": 0}, True)
        health("stale authentication", normal | {"lastHeartbeatSuccessAt": now - 1000, "lastLoginSuccessAt": now - 1000}, False)
        health("configured slow probe interval", normal | {"networkCheckIntervalSeconds": 300, "lastNetworkCheckCompletedAt": now - 250}, True)
        health("configured slow writer", normal | {"healthWriteIntervalSeconds": 300, "lastUpdatedAt": now - 600}, True)
        health("clock rollback recovered heartbeat", normal | {"lastLoginSuccessAt": 0}, True)
        # Duplicate field and interrupted file must never count as healthy.
        (root / "state/health.json").write_text(json.dumps(normal, indent=2).replace('"lastUpdatedAt":', '"lastUpdatedAt": 1700000000,\n  "lastUpdatedAt":'))
        assert run('sh docker/healthcheck.sh').returncode != 0; checks += 1; results.append("duplicate timestamp")
        (root / "state/health.json").write_text(json.dumps(normal, indent=2)[:-1])
        assert run('sh docker/healthcheck.sh').returncode != 0; checks += 1; results.append("interrupted file")

        def watchdog(label, data=None, mode="running", extra=None, pause=False, cooldown=None, recycle=False, command='sh scripts/openwrt-watchdog.sh'):
            nonlocal checks
            for name in ["calls", "sleeps", "inspects", "paused", "cooldown"]:
                (root / name).unlink(missing_ok=True)
            assert not (root / "lock").exists(), "Leaked watchdog lock"
            (root / "state/health.json").write_text(json.dumps(data or normal, indent=2), encoding="utf-8", newline="\n")
            if pause: (root / "paused").touch()
            if cooldown is not None: (root / "cooldown").write_text(str(cooldown))
            result = run(command, {"TEST_MODE": mode} | (extra or {}))
            assert result.returncode in (0, 7), (label, result.returncode, result.stderr)
            calls = (root / "calls").read_text() if (root / "calls").exists() else ""
            assert ("RECYCLE" in calls) == recycle, (label, calls, result.stderr)
            assert not any(line.startswith(("restart ", "start ", "stop ", "rm ")) for line in calls.splitlines())
            assert not (root / "lock").exists(), (label, "lock not released")
            checks += 1; results.append(label)

        watchdog("healthy liveness")
        watchdog("ownership pause is not recycled even when shared health is stale", normal | {"lastUpdatedAt": now - 1000}, mode="conflict")
        for mode in ["stopped", "missing", "paused", "restarting", "no-policy"]:
            watchdog("no recycle " + mode, normal | {"clientThreadAlive": False}, mode=mode)
        watchdog("explicit pause", normal | {"clientThreadAlive": False}, pause=True)
        watchdog("expected stopped", normal | {"clientThreadAlive": False}, extra={"EXPECTED_RUNNING": "0"})
        watchdog("failed WAN probes still show progress", normal | {"lastNetworkCheckAt": 0, "lastNetworkCheckSuccessAt": 0, "authenticated": False, "lastHeartbeatSuccessAt": 0, "lastLoginSuccessAt": 0})
        watchdog("blocked stage requests running process exit", normal | {"lastClientProgressAt": now - 200, "clientStageDeadlineAt": now - 80}, recycle=True)
        watchdog("fresh reporter cannot hide hung network thread", normal | {"lastNetworkCheckCompletedAt": now - 1000}, recycle=True)
        watchdog("future supported schema rejected", normal | {"lastUpdatedAt": now + 300}, recycle=True)
        watchdog("stop races recycle recheck", normal | {"clientThreadAlive": False}, mode="race", extra={"FAIL_THRESHOLD": "1"})
        watchdog("restart cooldown", normal | {"clientThreadAlive": False}, cooldown=now - 10)
        watchdog("rollback cooldown rebased", normal | {"clientThreadAlive": False}, cooldown=now + 300)
        watchdog("unknown schema not recycled", normal | {"schemaVersion": 1})
        # A live lock owner is the parent shell; second monitor must exit without docker calls.
        watchdog("single instance", command='mkdir "$LOCK_DIR"; echo $$ > "$LOCK_DIR/pid"; sh scripts/openwrt-watchdog.sh; rm "$LOCK_DIR/pid"; rmdir "$LOCK_DIR"')
        (root / "sleeps").unlink(missing_ok=True)
        result = run('sh scripts/measure-runtime.sh 2', {"INTERVAL_SECONDS": "1"})
        assert result.returncode == 0 and len(result.stdout.strip().splitlines()) == 3 and "20MiB" in result.stdout
        checks += 1; results.append("read-only runtime measurements")
        for script in ["scripts/measure-runtime.sh", "docker/health-state.sh", "docker/healthcheck.sh", "scripts/openwrt-watchdog.sh"]:
            result = run('sh -n ' + script)
            assert result.returncode == 0, result.stderr
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps({"checks": checks, "cases": results}, indent=2), encoding="utf-8")
    print(f"Docker supervision regression: {checks} checks passed")


if __name__ == "__main__":
    main()
