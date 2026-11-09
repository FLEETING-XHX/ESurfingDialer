import importlib.util
import tempfile
import unittest
import hashlib
from pathlib import Path

script = Path(__file__).resolve().parents[1] / 'scripts/build_provenance.py'
spec = importlib.util.spec_from_file_location('provenance', script)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)

class BuildProvenance(unittest.TestCase):
    def test_reject_stale_source_and_binary(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            (source / 'CMakeLists.txt').write_text('fixture')
            (source / 'Dockerfile').write_text('fixture')
            (source / 'src').mkdir()
            cpp = source / 'src/main.cpp'
            cpp.write_text('old source')
            digest = hashlib.sha256(b'binary').hexdigest()
            m = dict(schemaVersion=1, version='3.0.0', channel='experimental',
                     binary_sha256=digest, inputs=p.source_inputs(source))
            p.verify_build_provenance(source, m, digest, '3.0.0', 'experimental')
            with self.assertRaises(ValueError):
                p.verify_build_provenance(source, m, '0' * 64, '3.0.0', 'experimental')
            with self.assertRaises(ValueError):
                p.verify_build_provenance(source, {}, digest, '3.0.0', 'experimental')
            with self.assertRaises(ValueError):
                p.verify_build_provenance(source, m, digest, '3.0.0', 'stable')
            cpp.write_text('new source')
            with self.assertRaises(ValueError):
                p.verify_build_provenance(source, m, digest, '3.0.0', 'experimental')
            cpp.write_text('old source')
            (source / 'src/new.cpp').write_text('new input')
            with self.assertRaises(ValueError):
                p.verify_build_provenance(source, m, digest, '3.0.0', 'experimental')

if __name__ == '__main__':
    unittest.main()
