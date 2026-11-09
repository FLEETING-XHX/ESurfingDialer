"""Validate build-source.json against the current inputs and actual image binary."""
import hashlib
from pathlib import Path

DIRECTORIES = ('src', 'include', 'third_party', 'profiles', 'docker', 'cmake', 'scripts')

def source_inputs(source):
    source = Path(source)
    names = {'CMakeLists.txt', 'Dockerfile'}
    for directory in DIRECTORIES:
        for path in (source / directory).rglob('*'):
            if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc':
                names.add(path.relative_to(source).as_posix())
    return {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in sorted(names)}

def verify_build_provenance(source, manifest, binary_sha256, version, channel):
    if (manifest.get('schemaVersion') != 1 or manifest.get('version') != version
            or manifest.get('channel') != channel):
        raise ValueError('Image build identity does not match requested release')
    if manifest.get('binary_sha256') != binary_sha256:
        raise ValueError('Image binary does not match its build manifest')
    if manifest.get('inputs') != source_inputs(source):
        raise ValueError('Image source inputs differ from current source; rebuild required')
    return manifest
