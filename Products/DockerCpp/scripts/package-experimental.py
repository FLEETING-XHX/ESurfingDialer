#!/usr/bin/env python3
"""导出已构建且经过隔离验证的实验镜像，不推送或启动生产服务。"""
import argparse
import gzip
import hashlib
import json
import re
import shutil
import subprocess
import tarfile
import zipfile
from pathlib import Path
from build_provenance import verify_build_provenance


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def experimental_env_example(text):
    return '# Experimental test build: explicit opt-in required.\nNATIVE_EXPERIMENTAL=0\n' + text.replace('# Formal v3.0.0 does not require an experimental opt-in flag.\n','')


def experimental_compose(text):
    return (text.replace('    build: .\n', '')
        .replace('    build: {context: ., args: {NATIVE_RELEASE_CHANNEL: stable}}\n', '')
        .replace('      STATE_DIR: /data', '      STATE_DIR: /data\n      NATIVE_EXPERIMENTAL: "${NATIVE_EXPERIMENTAL:-0}"'))


def deployment_scripts(version, architectures=('amd64', 'arm64', 'armv7'), image_suffix='', experimental=True):
    """生成离线脚本，供打包与隔离回归共用。"""
    select = '''#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
case "$(uname -m)" in
 x86_64|amd64) arch=amd64 ;;
 aarch64|arm64) arch=arm64 ;;
 armv7l|armv7) arch=armv7 ;;
 *) echo 'Unsupported CPU architecture.' >&2; exit 2 ;;
esac
case ' @ARCHES@ ' in
 *" $arch "*) ;;
 *) echo 'This test package does not contain an image for this CPU architecture.' >&2; exit 2 ;;
esac
export DIALER_IMAGE="esurfing-dialer-native:v@VERSION@-$arch"
docker info >/dev/null
'''.replace('@VERSION@', version + image_suffix).replace('@ARCHES@', ' '.join(architectures))
    install = (select + '''[ -f .env ] || { umask 077; cp .env.example .env; }
docker load -i "images/esurfing-dialer-native-v@VERSION@-$arch.tar.gz"
sh ./prepare-log.sh
docker compose -f compose.native.yml create --no-build --pull never
printf '%s\n' 'Image imported and container created, not started. Edit .env, set credentials and NATIVE_EXPERIMENTAL=1, then run sh start.sh.'
'''.replace('@VERSION@', version + image_suffix))
    start = (select + '''[ -f .env ] || { echo 'Run sh install.sh first.' >&2; exit 2; }
# Use Compose's interpolation parser; never source/eval the credential file.
if ! config_environment="$(docker compose -f compose.native.yml config --environment)"; then
 echo 'Cannot parse Compose configuration. Check .env and Compose version. No container was started.' >&2
 exit 2
fi
case "
$config_environment
" in
 *'
NATIVE_EXPERIMENTAL=1
'*) ;;
 *) echo 'NATIVE_EXPERIMENTAL_REQUIRED: Set NATIVE_EXPERIMENTAL=1 in .env, then run sh start.sh. Docker UI start/restart does not reload Compose configuration. This is an experimental test build.' >&2; exit 78 ;;
esac
unset config_environment
for other in ESurfingDialer ESurfingDialer-v2.4 ESurfingDialer-v2.4.1 ESurfingDialer-Beta ESurfingDialer-Beta2 ESurfingDialer-Beta3 ESurfingDialerNative ESurfingDialerNative-v3.0.0; do
 running="$(docker inspect -f '{{.State.Running}}' "$other" 2>/dev/null || true)"
 [ "$running" != true ] || { printf 'Stop the other authentication container first: %s\n' "$other" >&2; exit 1; }
done
sh ./prepare-log.sh
docker compose -f compose.native.yml up -d --no-build --pull never
''')
    prepare = '''#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
[ -n "${DIALER_IMAGE:-}" ] || { echo 'Run install.sh or start.sh first.' >&2; exit 2; }
case "$PWD" in *,*) echo 'Installation path must not contain commas.' >&2; exit 2 ;; esac
[ ! -L log ] || { echo 'Refusing a symlink as the log directory.' >&2; exit 2; }
if [ -e log ]; then
 [ -d log ] || { echo 'log must be a dedicated directory.' >&2; exit 2; }
else
 (umask 077; mkdir log)
fi
# Change only the directory itself; never recursively change existing files.
# This helper has no network, credentials, state volume or authentication entrypoint.
docker run --rm --pull never --network none --read-only --user 0:0 \\
 --cap-drop ALL --cap-add CHOWN --cap-add FOWNER --security-opt no-new-privileges:true \\
 --mount "type=bind,src=$PWD/log,dst=/logs" --entrypoint /bin/sh "$DIALER_IMAGE" \\
 -c 'chown 10001:10001 /logs && chmod 700 /logs'
'''
    if not experimental:
        start = start[:start.index('# Use Compose')] + start[start.index('for other in '):]
        install = install.replace(', set credentials and NATIVE_EXPERIMENTAL=1', ', set credentials')
    return {'install.sh': install, 'start.sh': start, 'prepare-log.sh': prepare}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', default='3.0.0')
    parser.add_argument('--channel', choices=('experimental', 'stable'), default='experimental')
    parser.add_argument('--docker', default='C:/Users/FLEETING/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe')
    parser.add_argument('--architectures', nargs='+', choices=('amd64', 'arm64', 'armv7'), default=['amd64', 'arm64', 'armv7'])
    parser.add_argument('--image-suffix', default='')
    parser.add_argument('--package-suffix')
    parser.add_argument('--lifecycle-prefix', default='v300')
    parser.add_argument('--equivalence-report', type=Path)
    parser.add_argument('--compose-identity-suffix', default=None)
    parser.add_argument('--bootstrap-capture', action='store_true')
    parser.add_argument('--fix-notes', type=Path)
    parser.add_argument('--require-minimal-runtime', action='store_true')
    args = parser.parse_args()
    architecture_group = args.architectures[0] if len(args.architectures) == 1 else 'auto'
    if args.package_suffix is None:
        args.package_suffix = architecture_group if args.channel == 'stable' else 'experimental'
    source = Path(__file__).resolve().parent.parent
    repository = source.parents[1]
    version = args.version
    assert re.fullmatch(r'\d+\.\d+\.\d+', version), '版本格式无效'
    assert not args.image_suffix or re.fullmatch(r'-[a-z0-9-]+', args.image_suffix), '镜像后缀格式无效'
    assert args.compose_identity_suffix is None or re.fullmatch(r'-[a-z0-9-]+', args.compose_identity_suffix), 'Compose身份后缀格式无效'
    assert re.fullmatch(r'[a-z0-9-]+', args.package_suffix) and re.fullmatch(r'[a-z0-9-]+', args.lifecycle_prefix), '输出后缀格式无效'
    assert len(set(args.architectures)) == len(args.architectures), '架构重复'
    assert f'VERSION {version} ' in (source / 'CMakeLists.txt').read_text(), '源码版本不匹配'
    image_version = version + args.image_suffix
    compose_version = version + (args.compose_identity_suffix if args.compose_identity_suffix is not None else args.image_suffix)
    build = repository / 'artifacts/build/DockerCpp' / ('v' + image_version)
    name = 'ESurfingDialer-DockerCpp-v' + version + '-' + args.package_suffix
    output = repository / 'artifacts/packages/DockerCpp'
    if args.channel == 'stable':
        output = repository / 'artifacts/releases/OpenWrt-Docker/Cpp' / ('v' + version) / architecture_group
    package = output / ('unpacked' if args.channel == 'stable' else name)
    archive_path = output / (name + '.zip')
    assert not package.exists() and not archive_path.exists(), '保留已有输出，请勿覆盖'

    def docker(*arguments):
        return subprocess.check_output([args.docker, *arguments], text=True, encoding='utf-8').strip()

    metadata = []
    expected_images = {}
    equivalence = None
    if args.equivalence_report:
        equivalence = json.loads(args.equivalence_report.read_text(encoding='utf-8-sig'))
        assert equivalence['network'] == 'none' and not equivalence['server_authentication_tested']
        assert len(equivalence['runs']) == 3 and all(r['checks'] >= 329 and r['requests']==21 and r['independent_native_responses']==21 for r in equivalence['runs'])
        for path, expected in equivalence['source_sha256'].items():
            # 会话/算法/原生探针必须一致；运行脚本的产物元数据改动不影响等价性。
            if path.endswith(('.cpp', '.hpp', '.java', '.so', '/CMakeLists.txt', '/Dockerfile')):
                assert digest(repository / path) == expected, '离线等价证据与当前源码不一致: ' + path
    for arch, platform, expected_arch in [('amd64', 'linux/amd64', 'amd64'), ('arm64', 'linux/arm64', 'arm64'), ('armv7', 'linux/arm/v7', 'arm')]:
        if arch not in args.architectures:
            continue
        tag = 'esurfing-dialer-native:v' + image_version + '-' + arch
        info = json.loads(docker('image', 'inspect', tag))[0]
        assert info['Architecture'] == expected_arch and info['Os'] == 'linux'
        assert info['Config']['User'] == '10001:10001'
        assert info['Config']['Labels']['org.opencontainers.image.version'] == version
        assert info['Config']['Labels']['org.esurfing.release.channel'] == args.channel
        if args.require_minimal_runtime:
            assert info['Config']['Labels'].get('org.esurfing.runtime') == 'minimal-glibc', 'Minimal runtime image required'
        evidence = docker('run', '--rm', '--platform', platform, '--network', 'none', '--read-only', '--cap-drop', 'ALL',
                          '--entrypoint', '/bin/sh', tag, '-c', 'cat /app/build-source.json; printf "\\n"; sha256sum /app/esurfing-native')
        manifest_text, binary_line = evidence.rsplit('\n', 1)
        build_manifest = json.loads(manifest_text)
        verify_build_provenance(source, build_manifest, binary_line.split()[0], version, args.channel)
        expected_images[arch] = info
        log = (build / ('build-' + arch + '.log')).read_text(encoding='utf-8-sig')
        regression = re.search(r'100% tests passed, 0 tests failed out of (\d+)', log)
        assert regression and int(regression[1]) >= 20, '当前源码构建回归证据不完整'
        skipped = len(re.findall(r'Test\s+#\d+:.*?Skipped', log))
        lifecycle_path = source / ('build-msvc/' + args.lifecycle_prefix + '-' + arch + '-report.json')
        lifecycle = json.loads(lifecycle_path.read_text(encoding='utf-8-sig'))
        assert lifecycle['image_id'] == info['Id'] and lifecycle['same_user_term'] and lifecycle['exit_code'] == 0
        assert lifecycle['network'] == 'none' and not lifecycle['production_replaced']
        assert digest(source / 'profiles/android32-2082-loader.so') in lifecycle['layout'], '运行镜像缺少受控loader数据验证'
        text = docker('run', '--rm', '--platform', platform, '--network', 'none', '--read-only', '--cap-drop', 'ALL', tag, '--version')
        assert f'Native {version} ({args.channel})' in text, '程序版本不匹配'
        metadata.append(dict(architecture=arch, platform=platform, image=tag, image_id=info['Id'], image_bytes=info['Size'], runtime=info['Config']['Labels'].get('org.esurfing.runtime','full'), regression_groups=int(regression[1]), regression_passed=int(regression[1])-skipped, regression_skipped=skipped, lifecycle=True, version_output=text, build_manifest=build_manifest))

    (package / 'images').mkdir(parents=True)
    for image in metadata:
        arch = image['architecture']
        raw = build / ('image-' + arch + '.tar')
        assert not raw.exists(), '保留已有导出镜像'
        subprocess.run([args.docker, 'image', 'save', '--platform', image['platform'], '-o', str(raw), image['image']], check=True)
        compressed = package / 'images' / ('esurfing-dialer-native-v' + image_version + '-' + arch + '.tar.gz')
        with raw.open('rb') as incoming, gzip.open(compressed, 'wb', compresslevel=6) as outgoing:
            shutil.copyfileobj(incoming, outgoing)
        with gzip.open(compressed, 'rb') as stream:
            assert hashlib.file_digest(stream, 'sha256').hexdigest() == digest(raw)
        with tarfile.open(compressed, 'r:gz') as archive:
            manifest = json.load(archive.extractfile('manifest.json'))
            assert image['image'] in manifest[0]['RepoTags']
            config = json.load(archive.extractfile(manifest[0]['Config']))
            assert config['os'] == 'linux' and config['config']['User'] == '10001:10001'
            assert config['architecture'] == ('arm' if arch == 'armv7' else arch)
        image.update(file=compressed.relative_to(package).as_posix(), sha256=digest(compressed), compressed_bytes=compressed.stat().st_size)
        # 仅重新导入本轮同名同ID镜像，不启动容器；验证离线导入路径。
        loaded = docker('image', 'load', '-i', str(compressed))
        (build / ('load-' + arch + '.log')).write_text(loaded, encoding='utf-8')
        imported = json.loads(docker('image', 'inspect', image['image']))[0]
        # 选平台导出可以改变外层索引身份；配置与所有文件系统层必须保持相同。
        assert imported['RootFS'] == expected_images[arch]['RootFS']
        assert imported['Config'] == expected_images[arch]['Config']
        image['loaded_image_id'] = imported['Id']

    def write(name, text):
        path=package/name
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(text, encoding='utf-8', newline='\n')

    compose = experimental_compose((source / 'compose.native.yml').read_text(encoding='utf-8'))
    if args.channel == 'stable':
        compose = compose.replace('      NATIVE_EXPERIMENTAL: "${NATIVE_EXPERIMENTAL:-0}"\n', '')
    if args.image_suffix or args.compose_identity_suffix is not None:
        compose = compose.replace('esurfing-native-v300', 'esurfing-native-' + compose_version.replace('.', '-'))
        compose = compose.replace('ESurfingDialerNative-v' + version, 'ESurfingDialerNative-v' + compose_version)
        compose = compose.replace('esurfing-dialer-native:v' + version + '-amd64', 'esurfing-dialer-native:v' + image_version + '-amd64')
    write('compose.native.yml', compose)
    container_name = re.search(r'^    container_name: (\S+)$', compose, re.MULTILINE)[1]
    env_example = (source / '.env.example').read_text(encoding='utf-8')
    write('.env.example', experimental_env_example(env_example) if args.channel == 'experimental' else env_example)
    for script_name, script in deployment_scripts(version, args.architectures, args.image_suffix, args.channel == 'experimental').items():
        write(script_name, script)
    if args.bootstrap_capture:
        write('start-capture.sh', '#!/bin/sh\nset -eu\ncd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"\nexport NATIVE_CAPTURE_BOOTSTRAP=1\nexec sh ./start.sh\n')
        write('export-diagnostics.sh', '''#!/bin/sh
set -eu
umask 077
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
c=@CONTAINER@
mkdir -p results
output="$(mktemp -d "$PWD/results/cpp-XXXXXX")"
docker inspect --format 'image={{.Config.Image}} image_id={{.Image}} running={{.State.Running}}' "$c" > "$output/container.txt"
docker logs --tail 200 "$c" > "$output/docker.log" 2>&1
docker cp "$c":/data/health.json "$output/health.json"
docker cp "$c":/data/logs "$output/logs"
if ! docker cp "$c":/data/diagnostics "$output/diagnostics"; then
 echo '未取得bootstrap快照；请保留日志确认BOOTSTRAP_CAPTURE_FAILED或是否已启用新镜像。' >&2
fi
printf '取证目录：%s\\n原始.bin只交付到开发机本地Log目录，不公开。\\n' "$output"
'''.replace('@CONTAINER@', container_name))
    write('scripts/openwrt-watchdog.sh', (source / 'scripts/openwrt-watchdog.sh').read_text(encoding='utf-8').replace('ESurfingDialerNative-v3.0.0}',container_name+'}'))
    write('collect-host-diagnostics.sh', (source / 'scripts/collect-host-diagnostics.sh').read_text(encoding='utf-8').replace('ESurfingDialerNative-v3.0.0}',container_name+'}'))
    write('VERSION', 'v' + version + '\n')
    (package / 'licenses').mkdir()
    shutil.copyfile(source / 'third_party/ciphers/LICENSE', package / 'licenses/ciphers-APACHE-2.0')
    shutil.copyfile(source / 'third_party/NOTICE.md', package / 'licenses/NOTICE.md')
    shutil.copyfile(source / 'profiles/README.md', package / 'licenses/loader-data-source.md')
    info = dict(version='v' + version, image_version=image_version, compose_identity_version=compose_version, bootstrap_capture_available=args.bootstrap_capture, host_diagnostics_available=True, experimental_opt_in_required=True, restart_policy='unless-stopped', container_name=container_name, channel='experimental', production_ready=False, real_authentication=False,
                source_revision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repository, text=True).strip(),
                source_dirty=bool(subprocess.check_output(['git', 'status', '--porcelain', '--', '.'], cwd=repository, text=True).strip()),
                docker_load_tested=True,
                native_equivalence=False, offline_module_equivalence=bool(equivalence), equivalence_scope=equivalence['scope'] if equivalence else None,
                physical_arm_tested=False, arm_emulated=any(i['architecture'] != 'amd64' for i in metadata), startup_mode='create-only',
                images=metadata, source_sha256={p.relative_to(source).as_posix():digest(p) for p in source.rglob('*') if p.is_file() and not any(part.startswith('build') for part in p.relative_to(source).parts) and (p.name == '.env.example' or not p.name.startswith('.env'))})
    info.update(channel=args.channel, experimental_opt_in_required=args.channel=='experimental', production_ready=args.channel=='stable', user_reported_amd64_field_acceptance=args.channel=='stable', physical_arm_tested=False)
    write('BUILDINFO.json', json.dumps(info, ensure_ascii=False, indent=2))
    if equivalence:
        write('OFFLINE-VALIDATION.json', json.dumps(equivalence, ensure_ascii=False, indent=2))
    write('README.md', f"""# ESurfingDialer Docker C++ {version}

Runtime stdout uses concise UTF-8 Chinese operator summaries. File logs in ./log
retain complete bounded diagnostics and English event/error identifiers for analysis.
Optional WAN_INTERFACE and WAN_PHYSICAL_INTERFACE identify real kernel devices.
With both empty, the IPv4 default route supplies only an unconfirmed exit candidate.
Full interface inventory is file-only; relevant fault summaries appear on stdout.
If the previous process ended unexpectedly, use `sh collect-host-diagnostics.sh`
for Docker exit/OOM evidence. A killed process cannot report its own final cause.

Experimental build. Offline checks do not establish long-term server compatibility.
See BUILDINFO.json and OFFLINE-VALIDATION.json (when included) for validation scope.

1. Extract into a new directory. Preserve your previous .env and device identity.
2. Stop the previous native test container before installation.
3. Run `sh install.sh` to load the local image and create the container.
4. Edit `.env`: set credentials and NATIVE_EXPERIMENTAL=1.
5. Stop the Kotlin container, then run `sh start.sh`.
6. Run `docker compose -f compose.native.yml logs --tail 80`.
   Success requires LOGIN_CONFIRMED after the first heartbeat.
   File logs are in ./log on the router (visible in WinSCP).
   HEARTBEAT_OK is emitted first, then summarized every 30 minutes.
   NETWORK_STATE is emitted initially and on changes only.
   File logs keep today and the previous two local calendar dates (hourly cleanup).
   OPERATION_FAILED records stage, error class, timing and retry metadata.
   It does not prove that a timeout was caused by the server or WAN link.
   Optional router snapshot: `sh collect-host-diagnostics.sh > host-diagnostics.txt`.
   RESTART_PLAN shows the daily container restart window (UTC+8, default 04:00-06:00).
   The first eligible check inside the window requests a clean exit; Docker's
   unless-stopped policy restarts it. Missed windows are skipped, with no late
   catch-up. SCHEDULED_RESTART_TRIGGERED records the persisted daily claim.

If authentication is not confirmed within two minutes, stop this test:
`docker compose -f compose.native.yml stop`
Then restore Kotlin: `docker start ESurfingDialer-v2.4`.
Never run both authentication clients together or use `docker compose down -v`.
Preserve existing volumes and credentials. Do not share .env or raw diagnostics.

Optional bootstrap capture: `sh start-capture.sh`, then `sh export-diagnostics.sh`
(only when these scripts are included). Capture is disabled by default and captures
bootstrap modules only, not authentication responses.
Image: esurfing-dialer-native:v{image_version}. Container: {container_name}.
""")
    if args.channel == 'stable':
        write('README.md', f'''# ESurfingDialer Docker C++ v{version}

Stable release channel. No experimental flag is required.
Architectures included: {', '.join(args.architectures)}. install.sh selects and loads only the matching image.
amd64 field acceptance was reported by the user. ARM validation is emulated, not physical hardware acceptance.
See BUILDINFO.json and OFFLINE-VALIDATION.json for the exact validation scope.

1. Stop the old authentication container. Preserve its state volume and device identity.
2. Extract into a new directory and copy your existing .env, or create it from .env.example.
3. Run sh install.sh. Set credentials in .env, then run sh start.sh.
4. View concise UTF-8 Chinese operator messages with docker compose -f compose.native.yml logs --tail 80.
5. Detailed UTF-8 diagnostics are directly available in ./log. Keep today and the previous two local dates.

Restart policy: unless-stopped. Daily maintenance: once inside UTC+8 04:00-06:00 by default.
Manual stops stay stopped. Missed windows are skipped. Health JSON remains machine-readable.
Optional WAN_INTERFACE and WAN_PHYSICAL_INTERFACE must name real kernel devices.
Empty values provide only an unconfirmed default-route candidate, not proof of WAN identity.
Run sh collect-host-diagnostics.sh for optional read-only Docker exit/OOM and interface evidence.
Never run concurrent authentication clients or delete state with docker compose down -v.
If upgrade fails, stop the new container and restore your previous package with its preserved state.
''')
    write('SHA256SUMS', '\n'.join(digest(p) + '  ' + p.relative_to(package).as_posix() for p in sorted(package.rglob('*')) if p.is_file()) + '\n')
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive_path, 'x', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        for file in sorted(package.rglob('*')):
            if file.is_file(): archive.write(file, (Path(name) / file.relative_to(package)).as_posix())
    with zipfile.ZipFile(archive_path) as archive:
        assert archive.testzip() is None
        assert not any(n.endswith('/.env') or '/data/' in n for n in archive.namelist())
    (build / 'package-result.json').write_text(json.dumps(dict(path=str(archive_path), bytes=archive_path.stat().st_size, sha256=digest(archive_path), images=metadata), ensure_ascii=False, indent=2), encoding='utf-8')
    print('实验包:', archive_path)
    print('SHA256:', digest(archive_path))


if __name__ == '__main__':
    main()
