#!/usr/bin/env python3
"""隔离检查离线包安装/启动脚本；Docker写操作全部为替身。"""
import argparse
import hashlib
import json
import importlib.util
import os
import shutil
import subprocess
import tempfile
import re
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('package', type=Path, nargs='?')
    parser.add_argument('--source', action='store_true', help='从当前源码生成脚本夹具，不需要镜像或已有交付包')
    parser.add_argument('--architectures', nargs='+', choices=('amd64', 'arm64', 'armv7'), default=['amd64', 'arm64', 'armv7'])
    parser.add_argument('--image-suffix', default='')
    args = parser.parse_args()
    if args.source:
        assert args.package is None, '--source不能与包路径同时使用'
        fixture_package = tempfile.TemporaryDirectory(prefix='native-package-source-')
        package = Path(fixture_package.name)
        source = Path(__file__).resolve().parent.parent
        spec = importlib.util.spec_from_file_location('package_experimental', source / 'scripts/package-experimental.py')
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        for name, script in generator.deployment_scripts('3.0.0', args.architectures, args.image_suffix).items():
            (package / name).write_text(script, encoding='utf-8', newline='\n')
        for name in ('.env.example', 'compose.native.yml'):
            text=(source / name).read_text(encoding='utf-8')
            text=generator.experimental_env_example(text) if name=='.env.example' else generator.experimental_compose(text)
            (package / name).write_text(text, encoding='utf-8', newline='\n')
        container=re.search(r'^    container_name: (\S+)$',(package/'compose.native.yml').read_text(),re.M)[1]
        for name in ('collect-host-diagnostics.sh','openwrt-watchdog.sh'):
            destination=package/name if name.startswith('collect-') else package/'scripts'/name
            destination.parent.mkdir(parents=True,exist_ok=True)
            destination.write_text((source/'scripts'/name).read_text(encoding='utf-8').replace('ESurfingDialerNative-v3.0.0}',container+'}'),encoding='utf-8',newline='\n')
        (package / 'BUILDINFO.json').write_text(json.dumps(dict(production_ready=False, real_authentication=False,
            image_version='3.0.0'+args.image_suffix,
            images=[dict(architecture=arch, file=f'images/esurfing-dialer-native-v3.0.0{args.image_suffix}-{arch}.tar.gz') for arch in args.architectures])))
        (package / 'SHA256SUMS').write_text('')
    else:
        assert args.package is not None, '请提供包路径或--source'
        package = args.package.resolve()
    info = json.loads((package / 'BUILDINFO.json').read_text(encoding='utf-8'))
    checks = 0

    def verify(value, label):
        nonlocal checks
        assert value, label
        checks += 1

    verify(not info['production_ready'] and not info['real_authentication'], 'experimental status')
    verify('NATIVE_EXPERIMENTAL=0' in (package / '.env.example').read_text(encoding='utf-8'), 'opt in required')
    compose_text=(package/'compose.native.yml').read_text(encoding='utf-8')
    container=re.search(r'^    container_name: (\S+)$',compose_text,re.M)[1]
    verify('restart: unless-stopped' in compose_text,'daily restart uses Docker policy and respects manual stops')
    for name in ('collect-host-diagnostics.sh','scripts/openwrt-watchdog.sh'):
        verify('CONTAINER_NAME:-'+container+'}' in (package/name).read_text(encoding='utf-8'),'helper targets packaged container')
    for line in (package / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        expected, name = line.split('  ', 1)
        file = package / name
        with file.open('rb') as stream:
            verify(hashlib.file_digest(stream, 'sha256').hexdigest() == expected, 'checksum ' + name)
    shell = shutil.which('sh') or 'C:/Program Files/Git/bin/sh.exe'
    with tempfile.TemporaryDirectory(prefix='native-offline-package-') as temporary:
        root = Path(temporary)
        for name in ('install.sh', 'start.sh', 'prepare-log.sh', '.env.example', 'compose.native.yml'):
            shutil.copyfile(package / name, root / name)
        if info.get('bootstrap_capture_available'):
            shutil.copyfile(package / 'start-capture.sh', root / 'start-capture.sh')
        (root / 'bin').mkdir()
        scripts = {
            'uname': '#!/bin/sh\necho "$FIXTURE_CPU"\n',
            'docker': '''#!/bin/sh
printf '%s\n' "$*" >> "$CALLS"
printf '%s\n' "${DIALER_IMAGE:-}" >> "$SELECTED"
printf '%s\n' "${NATIVE_CAPTURE_BOOTSTRAP:-0}" >> "$CAPTURE_FLAGS"
case "$1" in
 info) exit 0 ;;
 load) [ -f "$3" ] || exit 91 ;;
 run) exit 0 ;;
 inspect) [ "$4" != "${RUNNING_OTHER:-}" ] || { echo true; exit 0; }; echo false ;;
 compose)
   if [ "$4" = config ]; then
     [ "${CONFIG_FAIL:-0}" = 0 ] || exit 93
     printf '%s\n' 'DIALER_PASSWORD=synthetic-MUST-NOT-LOG'
     sed -n '/^NATIVE_EXPERIMENTAL=/p' .env
   fi
   exit 0 ;;
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
        capture_flags = root / 'capture-flags'

        def run(script, cpu='x86_64', running='', config_fail='0'):
            calls.write_text(''); selected.write_text(''); capture_flags.write_text('')
            env = os.environ | dict(FIXTURE_CPU=cpu, RUNNING_OTHER=running, CONFIG_FAIL=config_fail, CALLS=calls.as_posix(), SELECTED=selected.as_posix(), CAPTURE_FLAGS=capture_flags.as_posix())
            return subprocess.run([shell, '-c', 'PATH="$PWD/bin:/usr/bin:/bin"; export PATH; exec sh "$1"', 'fixture', script], cwd=root, env=env, capture_output=True, timeout=15)

        for cpu, arch in [('x86_64', 'amd64'), ('aarch64', 'arm64'), ('armv7l', 'armv7')]:
            result = run('install.sh', cpu)
            if arch not in {image.get('architecture', 'amd64') for image in info['images']}:
                verify(result.returncode == 2 and not calls.read_text(), 'omitted architecture cannot invoke Docker')
                continue
            verify(result.returncode == 0, ('install', cpu, result.stderr))
            verify(f'v{info.get("image_version", "3.0.0")}-{arch}' in selected.read_text(), 'correct architecture')
            text = calls.read_text()
            verify(' load ' not in text and text.count('load -i ') == 1, 'one local image import')
            verify(' create --no-build --pull never' in text and ' up ' not in text, 'create only, no registry pull')
            verify((root / 'log').is_dir(), 'host log directory prepared')
            verify('--network none --read-only --user 0:0' in text and '--cap-drop ALL --cap-add CHOWN --cap-add FOWNER' in text, 'bounded log permission helper')
            verify('--entrypoint /bin/sh' in text and 'chmod 700 /logs' in text, 'helper cannot authenticate')
            sentinel = root / 'log' / 'preserve.txt'
            sentinel.write_text('preserve')
            config = root / '.env'
            config.write_text('NATIVE_EXPERIMENTAL=0\nOTHER_SETTING=preserve\n')
            before = config.read_bytes()
            verify(run('install.sh', cpu).returncode == 0 and config.read_bytes() == before, 'preserve user configuration')
            verify(sentinel.read_text() == 'preserve', 'existing log files preserved')
            result = run('start.sh', cpu)
            verify(result.returncode == 78 and 'NATIVE_EXPERIMENTAL_REQUIRED' in result.stderr.decode('utf-8'), 'disabled gate explains opt in')
            verify(' up ' not in calls.read_text() and 'inspect ' not in calls.read_text(), 'disabled gate cannot start containers')
            verify('synthetic-MUST-NOT-LOG' not in (result.stdout + result.stderr).decode('utf-8'), 'configuration secrets stay private')
            config.write_text('NATIVE_EXPERIMENTAL=1\nOTHER_SETTING=preserve\n')
            verify(run('start.sh', cpu).returncode == 0 and ' up -d --no-build --pull never' in calls.read_text(), 'explicit start only')
        for value in ('', 'NATIVE_EXPERIMENTAL=01\n', 'NATIVE_EXPERIMENTAL=true\n'):
            config.write_text(value)
            verify(run('start.sh').returncode == 78 and ' up ' not in calls.read_text(), 'missing/invalid opt in rejected')
        config.write_text('NATIVE_EXPERIMENTAL=1\n')
        if info.get('bootstrap_capture_available'):
            verify(run('start-capture.sh').returncode == 0 and ' up -d --no-build --pull never' in calls.read_text(), 'capture wrapper delegates guarded start')
            verify(set(capture_flags.read_text().splitlines())=={'1'}, 'capture opt in exported to Compose')
        verify(run('start.sh', config_fail='1').returncode == 2 and ' up ' not in calls.read_text(), 'Compose parse failure cannot start')
        for old in ('ESurfingDialer', 'ESurfingDialer-v2.4', 'ESurfingDialer-v2.4.1', 'ESurfingDialer-Beta', 'ESurfingDialer-Beta2', 'ESurfingDialer-Beta3', 'ESurfingDialerNative', 'ESurfingDialerNative-v3.0.0'):
            result = run('start.sh', running=old)
            verify(result.returncode == 1 and ' up ' not in calls.read_text(), 'reject running ' + old)
        verify(run('install.sh', cpu='mips').returncode == 2 and not calls.read_text(), 'unsupported architecture cannot invoke Docker')
    print(f'Offline experimental package: {checks} checks passed; no real Docker installation/start performed')


if __name__ == '__main__':
    main()
