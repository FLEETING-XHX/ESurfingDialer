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


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', default='3.0.0')
    parser.add_argument('--docker', default='C:/Users/FLEETING/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe')
    args = parser.parse_args()
    source = Path(__file__).resolve().parent.parent
    repository = source.parents[1]
    version = args.version
    assert re.fullmatch(r'\d+\.\d+\.\d+', version), '版本格式无效'
    assert f'VERSION {version} ' in (source / 'CMakeLists.txt').read_text(), '源码版本不匹配'
    build = repository / 'artifacts/build/DockerCpp' / ('v' + version)
    name = 'ESurfingDialer-DockerCpp-v' + version + '-experimental'
    output = repository / 'artifacts/packages/DockerCpp'
    package = output / name
    archive_path = output / (name + '.zip')
    assert not package.exists() and not archive_path.exists(), '保留已有输出，请勿覆盖'

    def docker(*arguments):
        return subprocess.check_output([args.docker, *arguments], text=True, encoding='utf-8').strip()

    metadata = []
    expected_images = {}
    for arch, platform, expected_arch in [('amd64', 'linux/amd64', 'amd64'), ('arm64', 'linux/arm64', 'arm64'), ('armv7', 'linux/arm/v7', 'arm')]:
        tag = 'esurfing-dialer-native:v' + version + '-' + arch
        info = json.loads(docker('image', 'inspect', tag))[0]
        assert info['Architecture'] == expected_arch and info['Os'] == 'linux'
        assert info['Config']['User'] == '10001:10001'
        assert info['Config']['Labels']['org.opencontainers.image.version'] == version
        assert info['Config']['Labels']['org.esurfing.release.channel'] == 'experimental'
        expected_images[arch] = info
        log = (build / ('build-' + arch + '.log')).read_text(encoding='utf-8-sig')
        assert '100% tests passed, 0 tests failed out of 17' in log, '构建回归证据不完整'
        lifecycle_path = source / ('build-msvc/v300-' + arch + '-report.json')
        lifecycle = json.loads(lifecycle_path.read_text(encoding='utf-8-sig'))
        assert lifecycle['image_id'] == info['Id'] and lifecycle['same_user_term'] and lifecycle['exit_code'] == 0
        assert lifecycle['network'] == 'none' and not lifecycle['production_replaced']
        text = docker('run', '--rm', '--platform', platform, '--network', 'none', '--read-only', '--cap-drop', 'ALL', tag, '--version')
        assert f'Native {version} (experimental;' in text, '程序版本不匹配'
        metadata.append(dict(architecture=arch, platform=platform, image=tag, image_id=info['Id'], image_bytes=info['Size'], regression_groups=17, lifecycle=True, version_output=text))

    (package / 'images').mkdir(parents=True)
    for image in metadata:
        arch = image['architecture']
        raw = build / ('image-' + arch + '.tar')
        assert not raw.exists(), '保留已有导出镜像'
        subprocess.run([args.docker, 'image', 'save', '--platform', image['platform'], '-o', str(raw), image['image']], check=True)
        compressed = package / 'images' / ('esurfing-dialer-native-v' + version + '-' + arch + '.tar.gz')
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
        (package / name).write_text(text, encoding='utf-8', newline='\n')

    compose = (source / 'compose.native.yml').read_text(encoding='utf-8').replace('    build: .\n', '')
    write('compose.native.yml', compose)
    write('.env.example', (source / '.env.example').read_text(encoding='utf-8'))
    select = '''#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
case "$(uname -m)" in
 x86_64|amd64) arch=amd64 ;;
 aarch64|arm64) arch=arm64 ;;
 armv7l|armv7) arch=armv7 ;;
 *) echo '不支持此CPU架构。' >&2; exit 2 ;;
esac
export DIALER_IMAGE="esurfing-dialer-native:v@VERSION@-$arch"
docker info >/dev/null
'''.replace('@VERSION@', version)
    write('install.sh', select + '''[ -f .env ] || { umask 077; cp .env.example .env; }
docker load -i "images/esurfing-dialer-native-v@VERSION@-$arch.tar.gz"
docker compose -f compose.native.yml create --no-build --pull never
printf '%s\n' '镜像已导入、容器已创建，尚未启动。请编辑.env，再执行sh start.sh。'
'''.replace('@VERSION@', version))
    write('start.sh', select + '''[ -f .env ] || { echo '请先运行 sh install.sh。' >&2; exit 2; }
for other in ESurfingDialer ESurfingDialer-v2.4 ESurfingDialer-v2.4.1 ESurfingDialer-Beta ESurfingDialer-Beta2 ESurfingDialer-Beta3 ESurfingDialerNative; do
 running="$(docker inspect -f '{{.State.Running}}' "$other" 2>/dev/null || true)"
 [ "$running" != true ] || { printf '请先手动停止旧容器：%s\n' "$other" >&2; exit 1; }
done
docker compose -f compose.native.yml up -d --no-build --pull never
''')
    (package / 'scripts').mkdir()
    write('scripts/openwrt-watchdog.sh', (source / 'scripts/openwrt-watchdog.sh').read_text(encoding='utf-8').replace('ESurfingDialerNative}', 'ESurfingDialerNative-v' + version + '}'))
    write('VERSION', 'v' + version + '\n')
    (package / 'licenses').mkdir()
    shutil.copyfile(source / 'third_party/ciphers/LICENSE', package / 'licenses/ciphers-APACHE-2.0')
    shutil.copyfile(source / 'third_party/NOTICE.md', package / 'licenses/NOTICE.md')
    shutil.copyfile(repository / 'Docs/DockerCpp/DOCKER-CPP-FIELD-ACCEPTANCE.md', package / '现场验收要求.md')
    info = dict(version='v' + version, channel='experimental', production_ready=False, real_authentication=False,
                source_revision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repository, text=True).strip(),
                source_dirty=bool(subprocess.check_output(['git', 'status', '--porcelain', '--', '.'], cwd=repository, text=True).strip()),
                docker_load_tested=True,
                native_equivalence=False, physical_arm_tested=False, arm_emulated=True, startup_mode='create-only',
                images=metadata, source_sha256={p.relative_to(source).as_posix():digest(p) for p in source.rglob('*') if p.is_file() and not any(part.startswith('build') for part in p.relative_to(source).parts) and (p.name == '.env.example' or not p.name.startswith('.env'))})
    write('BUILDINFO.json', json.dumps(info, ensure_ascii=False, indent=2))
    write('README.md', '''# Docker C++ v@VERSION@ 本地实验包

本包尚未完成真实校园网认证、Android32/2082原生等价、实体ARM路由器及长期稳定性验收。ARM镜像只经过CPU仿真；不能据此宣称可替换生产。

包含amd64、arm64、ARMv7三种离线镜像，安装脚本按CPU选择。本包不含Java，也不含真实账号、密码或用户状态。

1. 将整个目录上传到软路由，进入此目录，执行 `sh install.sh`。需要已安装Docker和Compose；只导入本地镜像、创建容器，不启动。
2. 编辑 `.env`，设置DIALER_USER、DIALER_PASSWORD，准备测试时显式设 `NATIVE_EXPERIMENTAL=1`。默认0会拒绝认证启动。
3. 先手动停止原Kotlin/其他认证容器，再运行 `sh start.sh`。不要让同一校园账号的多个认证程序同时运行。
4. 查看 `docker compose -f compose.native.yml logs --tail 80`。停止使用 `docker compose -f compose.native.yml stop`。
5. 回退时先停止本实验容器，再从原Kotlin目录启动原服务。本包使用独立命名卷；不要共用活动data目录、删除旧设备身份或执行down -v。

restart默认no，外置监控默认关闭；不配置自动重启。原Kotlin部署与数据不包含在本包，请自行保留。

三架构各17组构建回归和network:none/合成账户的实际PID1、健康与停止守卫检查通过；不是校园网验收。镜像及文件摘要见BUILDINFO.json和SHA256SUMS。请阅读现场验收要求.md。
'''.replace('@VERSION@', version))
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
