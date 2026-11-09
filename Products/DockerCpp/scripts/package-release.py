#!/usr/bin/env python3
"""Stable entry point sharing the verified exporter and provenance checks."""
import importlib.util
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location('native_packager', Path(__file__).with_name('package-experimental.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
if '--channel' in sys.argv or any(value.startswith('--channel=') for value in sys.argv):
    raise SystemExit('The release entry point always uses the stable channel')
sys.argv.extend(['--channel', 'stable'])
module.main()
