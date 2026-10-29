import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import build_evidence_manifest


class EvidenceToolTest(unittest.TestCase):
    def run_verify(self, root):
        return subprocess.run([sys.executable, str(HERE / 'verify_evidence_manifest.py'), str(root)], capture_output=True, text=True)

    def test_manifest_excludes_secrets_and_verifies_clean_copy(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'report.json').write_text('{"ok":true}', encoding='utf-8')
            (root / '.env').write_text('PASSWORD=secret', encoding='utf-8')
            (root / 'private.key').write_text('secret', encoding='utf-8')
            manifest = build_evidence_manifest.build(root, 'test')
            self.assertEqual(manifest['fileCount'], 1)
            self.assertEqual(manifest['files'][0]['path'], 'report.json')
            (root / 'evidence-manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
            result = self.run_verify(root)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('"valid": true', result.stdout)

    def test_verifier_detects_changed_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            target = root / 'report.json'
            target.write_text('before', encoding='utf-8')
            manifest = build_evidence_manifest.build(root, 'test')
            (root / 'evidence-manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
            target.write_text('after', encoding='utf-8')
            result = self.run_verify(root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('report.json', result.stdout)
            self.assertIn('"valid": false', result.stdout)

    def test_verifier_detects_unregistered_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'report.json').write_text('before', encoding='utf-8')
            manifest = build_evidence_manifest.build(root, 'test')
            (root / 'evidence-manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
            (root / 'new-log.txt').write_text('new', encoding='utf-8')
            result = self.run_verify(root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('new-log.txt', result.stdout)


if __name__ == '__main__':
    unittest.main()
