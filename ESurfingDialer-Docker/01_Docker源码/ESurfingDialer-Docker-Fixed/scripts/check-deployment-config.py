#!/usr/bin/env python3
"""执行生产文本生成片段、安装器和启动脚本；Docker全部由隔离替身接管。"""
import ast
import gzip
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True


def main():
    source = Path(__file__).resolve().parent.parent
    shell = shutil.which("sh") or "C:/Program Files/Git/bin/sh.exe"
    checks = 0

    def verify(value, label):
        nonlocal checks
        assert value, label
        checks += 1

    with tempfile.TemporaryDirectory(prefix="docker-deployment-regression-") as temporary:
        for filename, startname in (("package-release.py", "start.sh"), ("package-beta.py", "start-beta3.sh")):
            path = source / "scripts" / filename
            spec = importlib.util.spec_from_file_location(filename.replace("-", "_"), path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            folder = Path(temporary) / filename
            folder.mkdir()
            main_ast = next(n for n in ast.parse(path.read_text(encoding="utf-8")).body if isinstance(n, ast.FunctionDef) and n.name == "main")
            loop = next(n for n in main_ast.body if isinstance(n, ast.For) and isinstance(n.target, ast.Name) and n.target.id == "name")
            start = next(n for n in main_ast.body if isinstance(n, ast.Expr) and isinstance(n.value, ast.Call)
                         and isinstance(n.value.func, ast.Attribute) and n.value.func.attr == "write_text"
                         and startname in ast.unparse(n.value.func.value))
            scope = vars(module).copy() | {"source": source, "package": folder}
            exec(compile(ast.Module(body=[loop, start], type_ignores=[]), str(path), "exec"), scope)
            verify('name: ' + module.PROJECT in (folder / "docker-compose.yml").read_text(encoding="utf-8"), "Compose project")
            verify(module.TAG in (folder / "docker-compose.yml").read_text(encoding="utf-8"), "Compose image")
            template = (folder / ".env.example").read_text(encoding="utf-8")
            verify("DIALER_IMAGE=" + module.TAG in template, "environment image")
            (folder / ".env").write_text(template.replace('DIALER_USER=""', 'DIALER_USER="synthetic"').replace('DIALER_PASSWORD=""', 'DIALER_PASSWORD="fixture"'), encoding="utf-8", newline="\n")
            (folder / "images").mkdir()
            (folder / "images" / ("esurfing-dialer-" + module.VERSION + "-amd64.tar.gz")).write_bytes(gzip.compress(b"synthetic image"))
            (folder / "data").mkdir()
            identity = folder / "data/device-state.json"
            identity.write_text("preserve synthetic identity", encoding="utf-8")
            bin_dir = folder / "bin"
            bin_dir.mkdir()
            commands = {
                "uname": "#!/bin/sh\necho x86_64\n",
                "df": "#!/bin/sh\nprintf 'Filesystem 1024-blocks Used Available Capacity Mounted\\nfixture 4000000 0 4000000 0%% /\\n'\n",
                "docker": r'''#!/bin/sh
printf '%s\n' "$*" >> "$CALLS"
case "$1" in
 info) exit 0 ;;
 inspect)
   if [ "$2" != --format ]; then exit 1; fi
   [ "$4" != "${RUNNING_OTHER:-}" ] || { echo true; exit 0; }
   echo false ;;
 load) cat >/dev/null ;;
 compose)
   [ "$2" != version ] || exit 0
   [ "$2" = --project-name ] || exit 91
   project="$3"; shift 3
   case "$1" in
     create) printf '%s' "$project" > "$CREATED_PROJECT" ;;
     up) [ "$(cat "$CREATED_PROJECT")" = "$project" ] || exit 92 ;;
     *) exit 93 ;;
   esac ;;
 ps) exit 0 ;;
 *) exit 94 ;;
esac
''',
            }
            for name, content in commands.items():
                file = bin_dir / name
                file.write_text(content, encoding="utf-8", newline="\n")
                file.chmod(0o755)
            calls = folder / "calls.log"
            project = folder / "created-project"
            env = os.environ | {"CALLS": calls.as_posix(), "CREATED_PROJECT": project.as_posix(), "RUNNING_OTHER": ""}

            def run(name, arguments=(), running=""):
                calls.write_text("", encoding="utf-8")
                return subprocess.run([shell, "-c", 'PATH="$PWD/bin:/usr/bin:/bin"; export PATH; exec sh "$@"', "fixture", name, *arguments],
                    cwd=folder, env=env | {"RUNNING_OTHER": running}, capture_output=True, timeout=20)

            result = run("install.sh")
            verify(result.returncode == 0, (filename, "install", result.stderr))
            verify(project.read_text() == module.PROJECT, "installer project")
            verify(" up " not in calls.read_text(), "installation does not start authentication")
            verify(identity.read_text() == "preserve synthetic identity", "identity preserved")
            verify("DIALER_IMAGE=" + module.TAG in (folder / ".env").read_text(encoding="utf-8"), "installed image")
            for arguments in ((), ("--force-recreate",)):
                result = run(startname, arguments)
                verify(result.returncode == 0, (filename, "start", result.stderr))
                verify("compose --project-name " + module.PROJECT + " up -d" in calls.read_text(), "start uses installed project")
                verify("--force-recreate" in calls.read_text() if arguments else "--force-recreate" not in calls.read_text(), "start arguments")
            for old in ("ESurfingDialer", "ESurfingDialer-Beta", "ESurfingDialer-Beta2", "ESurfingDialer-Beta3", "ESurfingDialer-v2.4", "ESurfingDialer-v2.4.1"):
                if old == module.CONTAINER:
                    continue
                result = run(startname, running=old)
                verify(result.returncode == 1 and "compose " not in calls.read_text(), "blocks running " + old)
            result = run(startname, ("--unsupported",))
            verify(result.returncode == 2 and not calls.read_text(), "invalid arguments cannot operate Docker")
    print(f"Docker deployment config regression: {checks} checks passed (release + beta; no real Docker/images)")


if __name__ == "__main__":
    main()
