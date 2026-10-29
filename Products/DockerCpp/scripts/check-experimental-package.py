#!/usr/bin/env python3
"""隔离检查离线包安装/启动脚本；Docker写操作全部为替身。"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('package', type=Path)
    args = parser.parse_args()
    package = args.package.resolve()
    info = json.loads((package / 'BUILDINFO.json').read_text(encoding='utf-8'))
    checks = 0

    def verify(value, label):
        nonlocal checks
        assert value, label
        checks += 1

    verify(not info['production_ready'] and not info['real_authentication'], 'experimental status')
    verify('NATIVE_EXPERIMENTAL=0' in (package / '.env.example').read_text(encoding='utf-8'), 'opt in required')
    for line in (package / 'SHA256SUMS').read_text().splitlines():
        expected, name = line.split('  ', 1)
        file = package / name
        with file.open('rb') as stream:
            verify(hashlib.file_digest(stream, 'sha256').hexdigest() == expected, 'checksum ' + name)
    shell = shutil.which('sh') or 'C:/Program Files/Git/bin/sh.exe'
    with tempfile.TemporaryDirectory(prefix='native-offline-package-') as temporary:
        root = Path(temporary)
        for name in ('install.sh', 'start.sh', '.env.example', 'compose.native.yml'):
            shutil.copyfile(package / name, root / name)
        (root / 'bin').mkdir()
        scripts = {
            'uname': '#!/bin/sh\necho "$FIXTURE_CPU"\n',
            'docker': '''#!/bin/sh
printf '%s\n' "$*" >> "$CALLS"
printf '%s\n' "${DIALER_IMAGE:-}" >> "$SELECTED"
case "$1" in
 info) exit 0 ;;
 load) [ -f "$3" ] || exit 91 ;;
 inspect) [ "$4" != "${RUNNING_OTHER:-}" ] || { echo true; exit 0; }; echo false ;;
 compose) exit 0 ;;
 *) exit 92 ;;
esac
''',
        }
        for name, text in scripts.items():
            path = root / 'bin' / name
            path.write_text(text, encoding='utf-8', newline='\n')
            path.chmod(0o755)
        (root / 'images').mkdir()
        for image in info['images']:
            # 合成占位，不复制或导入真实镜像。
            (root / image['file']).write_bytes(b'synthetic docker substitute')
        calls, selected = root / 'calls', root / 'selected'

        def run(script, cpu='x86_64', running=''):
            calls.write_text(''); selected.write_text('')
            env = os.environ | dict(FIXTURE_CPU=cpu, RUNNING_OTHER=running, CALLS=calls.as_posix(), SELECTED=selected.as_posix())
            return subprocess.run([shell, '-c', 'PATH="$PWD/bin:/usr/bin:/bin"; export PATH; exec sh "$1"', 'fixture', script], cwd=root, env=env, capture_output=True, timeout=15)

        for cpu, arch in [('x86_64', 'amd64'), ('aarch64', 'arm64'), ('armv7l', 'armv7')]:
            result = run('install.sh', cpu)
            verify(result.returncode == 0, ('install', cpu, result.stderr))
            verify(f'v3.0.0-{arch}' in selected.read_text(), 'correct architecture')
            text = calls.read_text()
            verify(' load ' not in text and text.count('load -i ') == 1, 'one local image import')
            verify(' create --no-build --pull never' in text and ' up ' not in text, 'create only, no registry pull')
            config = root / '.env'
            config.write_text('NATIVE_EXPERIMENTAL=0\nOTHER_SETTING=preserve\n')
            before = config.read_bytes()
            verify(run('install.sh', cpu).returncode == 0 and config.read_bytes() == before, 'preserve user configuration')
            verify(run('start.sh', cpu).returncode == 0 and ' up -d --no-build --pull never' in calls.read_text(), 'explicit start only')
        for old in ('ESurfingDialer', 'ESurfingDialer-v2.4', 'ESurfingDialer-v2.4.1', 'ESurfingDialer-Beta', 'ESurfingDialer-Beta2', 'ESurfingDialer-Beta3', 'ESurfingDialerNative'):
            result = run('start.sh', running=old)
            verify(result.returncode == 1 and 'compose ' not in calls.read_text(), 'reject running ' + old)
        verify(run('install.sh', cpu='mips').returncode == 2 and not calls.read_text(), 'unsupported architecture cannot invoke Docker')
    print(f'Offline experimental package: {checks} checks passed; no real Docker installation/start performed')


if __name__ == '__main__':
    main()
