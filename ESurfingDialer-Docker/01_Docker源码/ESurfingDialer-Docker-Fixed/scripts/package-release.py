#!/usr/bin/env python3
"""Build the v2.4 amd64 release archive from a verified released runtime and new JVM code.
No daemon, registry push, credentials, or modification of the original image is needed.
OCI layout reference: https://github.com/opencontainers/image-spec/blob/main/image-layout.md
"""
import argparse
import datetime
import gzip
import hashlib
import io
import json
import shutil
import subprocess
from pathlib import Path
import tarfile
import zipfile

BASE_SHA256 = "93bca5389a75eb997f1aec0d0316f433401e758129f01d7fa862ffb2bb9fb5fd"
VERSION = "v2.4"
CONTAINER = "ESurfingDialer-v2.4"
PROJECT = "esurfing-dialer-v24"
TAG = "esurfing-dialer:" + VERSION
NAME = "ESurfingDialer-Docker-" + VERSION


def digest(data):
    return hashlib.sha256(data).hexdigest()


def serialized(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()


def add_file(archive, name, data, mode=0o644):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = mode
    info.mtime = 0
    archive.addfile(info, io.BytesIO(data))


def blob(archive, data):
    hash_value = digest(data)
    add_file(archive, "blobs/sha256/" + hash_value, data)
    return "sha256:" + hash_value


def image(base, jar, source, output, build_id):
    now = datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
    layer_bytes = io.BytesIO()
    with tarfile.open(fileobj=layer_bytes, mode="w", format=tarfile.PAX_FORMAT) as layer:
        add_file(layer, "app/client.jar", jar.read_bytes())
        for script in ["entrypoint.sh", "healthcheck.sh", "health-state.sh", "log-maintenance.sh", "log-sink.awk"]:
            add_file(layer, "app/" + script, (source / "docker" / script).read_bytes().replace(b"\r\n", b"\n"), 0o755)
    new_layer = layer_bytes.getvalue()
    with tarfile.open(base, "r:gz") as original, tarfile.open(output, "w:gz", compresslevel=6) as target:
        old = json.load(original.extractfile("manifest.json"))[0]
        config = json.load(original.extractfile(old["Config"]))
        assert config["architecture"] == "amd64" and config["os"] == "linux"
        assert len(config["rootfs"]["diff_ids"]) == len(old["Layers"])
        descriptors = []
        paths = []
        # Uncompressed blobs support both OCI and Docker manifest.json importers.
        for path, expected in zip(old["Layers"], config["rootfs"]["diff_ids"]):
            archived = original.extractfile(path).read()
            assert digest(archived) == path.split("/")[-1], "Base blob digest mismatch"
            data = gzip.decompress(archived) if archived.startswith(b"\x1f\x8b") else archived
            layer_digest = blob(target, data)
            assert layer_digest == expected, "Base rootfs digest mismatch"
            descriptors.append({"mediaType": "application/vnd.oci.image.layer.v1.tar", "digest": layer_digest, "size": len(data)})
            paths.append("blobs/sha256/" + layer_digest.split(":")[1])
        new_digest = blob(target, new_layer)
        paths.append("blobs/sha256/" + new_digest.split(":")[1])
        descriptors.append({"mediaType": "application/vnd.oci.image.layer.v1.tar", "digest": new_digest, "size": len(new_layer)})
        config["rootfs"]["diff_ids"].append(new_digest)
        config["created"] = now
        config.setdefault("history", []).append({"created": now, "created_by": "Release v2.4: verified v1.3 runtime + updated client.jar and updated runtime scripts"})
        config["config"].setdefault("Labels", {}).update({"org.opencontainers.image.version": VERSION, "esurfing.release.channel": "stable", "esurfing.base.sha256": BASE_SHA256,
            "esurfing.build.id": build_id, "esurfing.jar.sha256": digest(jar.read_bytes())})
        config_data = serialized(config)
        config_digest = blob(target, config_data)
        manifest = {"schemaVersion": 2, "mediaType": "application/vnd.oci.image.manifest.v1+json",
                    "config": {"mediaType": "application/vnd.oci.image.config.v1+json", "digest": config_digest, "size": len(config_data)}, "layers": descriptors}
        manifest_data = serialized(manifest)
        manifest_digest = blob(target, manifest_data)
        index = {"schemaVersion": 2, "mediaType": "application/vnd.oci.image.index.v1+json", "manifests": [
            {"mediaType": manifest["mediaType"], "digest": manifest_digest, "size": len(manifest_data), "platform": {"architecture": "amd64", "os": "linux"},
             "annotations": {"io.containerd.image.name": "docker.io/library/" + TAG, "org.opencontainers.image.ref.name": VERSION}}]}
        add_file(target, "oci-layout", serialized({"imageLayoutVersion": "1.0.0"}))
        add_file(target, "index.json", serialized(index))
        add_file(target, "manifest.json", serialized([{"Config": "blobs/sha256/" + config_digest.split(":")[1], "RepoTags": [TAG], "Layers": paths}]))
    return {"imageConfig": config_digest, "imageManifest": manifest_digest, "jarSha256": digest(jar.read_bytes()), "baseSha256": BASE_SHA256}


def verify_image(path, jar):
    with tarfile.open(path, "r:gz") as archive:
        def checked(descriptor):
            data = archive.extractfile("blobs/sha256/" + descriptor["digest"].split(":")[1]).read()
            assert len(data) == descriptor["size"] and "sha256:" + digest(data) == descriptor["digest"]
            return data
        index = json.load(archive.extractfile("index.json"))
        manifest = json.loads(checked(index["manifests"][0]))
        config = json.loads(checked(manifest["config"]))
        for descriptor, expected in zip(manifest["layers"], config["rootfs"]["diff_ids"]):
            assert descriptor["digest"] == expected
            checked(descriptor)
        assert len(manifest["layers"]) == len(config["rootfs"]["diff_ids"])
        with tarfile.open(fileobj=io.BytesIO(checked(manifest["layers"][-1]))) as layer:
            assert layer.extractfile("app/client.jar").read() == jar.read_bytes()
        compatibility = json.load(archive.extractfile("manifest.json"))[0]
        assert compatibility["RepoTags"] == [TAG]
        assert compatibility["Config"].endswith(manifest["config"]["digest"].split(":")[1])
        assert compatibility["Layers"] == ["blobs/sha256/" + x["digest"].split(":")[1] for x in manifest["layers"]]
        assert config["config"]["Entrypoint"] == ["/app/entrypoint.sh"]
    print("Image blobs, rootfs, OCI/Docker manifests, and packaged JAR verified")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-id", default=VERSION)
    args = parser.parse_args()
    source = Path(__file__).resolve().parent.parent
    assert hashlib.file_digest(args.base.open("rb"), "sha256").hexdigest() == BASE_SHA256, "Released base checksum mismatch"
    package = args.output / NAME
    assert not package.exists(), "Output already exists; preserve it and choose another output directory"
    package.mkdir(parents=True)
    (package / "images").mkdir()
    image_path = package / "images" / ("esurfing-dialer-" + VERSION + "-amd64.tar.gz")
    metadata = image(args.base, args.jar, source, image_path, args.build_id)
    metadata.update(version=VERSION, containerName=CONTAINER, composeProject=PROJECT,
        sourceRevision=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip(),
        sourceDirty=bool(subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal", "--", "."], cwd=source, text=True).strip()), buildId=args.build_id)
    metadata.update(architecture="linux/amd64", startupMode="create-only", deliveryMethod="verified-v1.3-runtime-plus-current-jar", daemonBuildVerified=False)
    source_files = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", "."], cwd=source).decode("utf-8").split("\0")
    metadata["sourceFilesSha256"] = {name: digest((source / name).read_bytes()) for name in sorted(set(source_files)) if name and (source / name).is_file()}
    verify_image(image_path, args.jar)
    for name in [".env.example", "docker-compose.yml", "install.sh", "LICENSE", "CHANGELOG.md"]:
        text = (source / name).read_text(encoding="utf-8")
        if name != "CHANGELOG.md":
            text = text.replace("v1.3", VERSION)
        if name == "docker-compose.yml":
            text = "name: " + PROJECT + "\n" + text.replace("container_name: ESurfingDialer", "container_name: " + CONTAINER)
        if name == "install.sh":
            text = text.replace('aarch64|arm64)\n    package_arch="arm64"', 'aarch64|arm64)\n    fail "This release package supports amd64 only."\n    package_arch="arm64"')
            text = text.replace("docker compose up -d", "docker compose --project-name " + PROJECT + " up -d")
            text = text.replace('name=ESurfingDialer"', 'name=^/' + CONTAINER + '$"').replace("--tail 80 ESurfingDialer", "--tail 80 " + CONTAINER)
            text = text.replace("Starting ESurfingDialer with", "Starting " + CONTAINER + " with")
            text = text.replace('command -v docker', 'cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"\n\ncommand -v docker', 1)
            # Never operate on a same-named container belonging to another deployment.
            anchor = 'free_kb="$(df'
            preflight = 'if docker inspect ' + CONTAINER + ' >/dev/null 2>&1; then\n  fail "' + CONTAINER + ' already exists. Use the v2.4 directory and explicit Compose commands to update it."\nfi\n\n'
            text = text.replace(anchor, preflight + anchor, 1)
            # Deploy alongside stable, but leave authentication explicitly stopped.
            text = text.replace("Starting " + CONTAINER + " with local image only.", "Creating " + CONTAINER + " without starting it. Run sh start.sh when ready.")
            text = text.replace("docker compose --project-name " + PROJECT + " up -d --no-build --pull never", "docker compose --project-name " + PROJECT + " create --no-build --pull never")
            text = text.replace("docker ps --filter", "docker ps -a --filter")
            text = text.split('info "Recent logs:"')[0] + 'info "Container created but not started. Stop the stable container, then run sh start.sh."\n'
        (package / name).write_text(text, encoding="utf-8", newline="\n")
    # Optional rebuild uses exactly the packaged JAR/scripts; offline import remains available.
    (package / "build/libs").mkdir(parents=True)
    shutil.copyfile(args.jar, package / "build/libs" / args.jar.name)
    (package / "Dockerfile.runtime").write_text((source / "Dockerfile.runtime").read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
    (package / "docker-compose.build.yml").write_text(
        "services:\n  esurfing-dialer:\n    build:\n      context: .\n      dockerfile: Dockerfile.runtime\n", encoding="utf-8", newline="\n")
    (package / ".dockerignore").write_text("*\n!Dockerfile.runtime\n!build/\n!build/libs/\n!build/libs/*-all.jar\n!docker/\n!docker/*.sh\n!docker/*.awk\n", encoding="ascii", newline="\n")
    (package / "start.sh").write_text(r'''#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
case "$#:${1:-}" in
  0:) ;;
  1:--force-recreate) ;;
  *) printf 'Usage: sh start.sh [--force-recreate]\n' >&2; exit 2 ;;
esac
docker info >/dev/null
for other in ESurfingDialer ESurfingDialer-Beta ESurfingDialer-Beta2 ESurfingDialer-Beta3; do
  running="$(docker inspect --format '{{.State.Running}}' "$other" 2>/dev/null || true)"
  if [ "$running" = true ]; then
    printf 'ERROR: %s is running. Stop it before starting ESurfingDialer-v2.4.\n' "$other" >&2
    exit 1
  fi
done
docker compose --project-name esurfing-dialer-v24 up -d "$@" --no-build --pull never
''', encoding="utf-8", newline="\n")
    (package / "scripts").mkdir()
    (package / "docker").mkdir()
    for runtime_file in (source / "docker").iterdir():
        if runtime_file.suffix in (".sh", ".awk"):
            (package / "docker" / runtime_file.name).write_bytes(runtime_file.read_bytes().replace(b"\r\n", b"\n"))
    watchdog = (source / "scripts/openwrt-watchdog.sh").read_text(encoding="utf-8")
    watchdog = watchdog.replace("${CONTAINER_NAME:-ESurfingDialer}", "${CONTAINER_NAME:-" + CONTAINER + "}")
    (package / "scripts/openwrt-watchdog.sh").write_text(watchdog, encoding="utf-8", newline="\n")
    (package / "scripts/measure-runtime.sh").write_text((source / "scripts/measure-runtime.sh").read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
    (package / "docker/health-state.sh").write_text((source / "docker/health-state.sh").read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
    (package / "README_RELEASE.md").write_text((source / "README_RELEASE.md").read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
    shutil.copyfile(source / "README_RELEASE.md", package / "README.md")
    (package / "VERSION").write_text(VERSION + "\n", encoding="utf-8")
    (package / "BUILD_ID").write_text(args.build_id + "\n", encoding="utf-8")
    (package / "BUILDINFO.json").write_bytes(serialized(metadata))
    checksums = []
    for file in sorted(package.rglob("*")):
        if file.is_file():
            checksums.append(hashlib.file_digest(file.open("rb"), "sha256").hexdigest() + "  " + file.relative_to(package).as_posix())
    (package / "SHA256SUMS").write_text("\n".join(checksums) + "\n", encoding="ascii", newline="\n")
    zip_path = args.output / (NAME + ".zip")
    assert not zip_path.exists(), "ZIP already exists"
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        for file in sorted(package.rglob("*")):
            if file.is_file():
                archive.write(file, (Path(NAME) / file.relative_to(package)).as_posix())
    with zipfile.ZipFile(zip_path) as archive:
        assert archive.testzip() is None
        assert not any(name.endswith("/.env") or "/data/" in name for name in archive.namelist())
    print("Package:", zip_path)
    print("ZIP SHA256:", hashlib.file_digest(zip_path.open("rb"), "sha256").hexdigest())


if __name__ == "__main__":
    main()
