import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

COMMAND = Path(__file__).resolve().parent / 'scanlab'

class PixelChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bundle = self.root / 'scan'
        self.bundle.mkdir()
        self.native = {'format': 'org.lji.vialscanner.debug', 'schemaVersion': 1,
            'scan': {'id': 'test', 'status': 'finished', 'startedAtEpochMs': 0, 'endedAtEpochMs': 1000},
            'config': {'mode': 'performance'}, 'thermal': {'startStatus': 0, 'endStatus': 0},
            'result': {'verdict': 'CONFIDENT', 'abNo': None, 'fields': {'CATALOG': '999999'}},
            'frames': [{'index': 0, 'timestampSeconds': 0, 'ocrAttempted': True, 'barcodeAttempted': True,
                'observations': [{'text': 'CAT: 999999'}], 'barcodes': [], 'ocrMs': 40, 'barcodeMs': 10, 'ingestMs': 60}]}
        self.truth = self.root / 'truth.json'
        self.truth.write_text(json.dumps({'mode': 'known_antibody', 'fields': {'catalog': '999999', 'abNo': None}, 'requiredFields': ['catalog', 'abNo']}))

    def run_check(self, truth=True):
        (self.bundle / 'bundle.json').write_text(json.dumps(self.native))
        args = [sys.executable, str(COMMAND), 'check', str(self.bundle), '--pixel', '--json']
        if truth: args += ['--truth', str(self.truth)]
        result = subprocess.run(args, capture_output=True, text=True)
        self.assertNotIn('Traceback', result.stderr)
        return result.returncode, json.loads(result.stdout)

    def test_real_shape_checks_fields_and_does_not_count_timing_as_barcode(self):
        code, report = self.run_check()
        self.assertEqual(code, 0, report)
        self.assertEqual(report['stats']['barcodes'], 0)
        self.assertEqual(report['stats']['stages']['ocr']['medianMs'], 40)

    def test_missing_independent_truth_is_not_green(self):
        code, report = self.run_check(False)
        self.assertEqual(code, 1)
        self.assertEqual(report['outcome'], 'invalid')

    def test_wrong_inventory_selection_fails_even_when_product_matches(self):
        self.native['result']['abNo'] = '16'
        code, report = self.run_check()
        self.assertEqual(code, 1)
        self.assertTrue(any('abNo' in m for m in report['mismatches']))

    def test_wrong_catalog_fails(self):
        self.native['result']['fields']['CATALOG'] = '888888'
        self.assertEqual(self.run_check()[0], 1)

    def test_cancelled_scan_cannot_pass(self):
        self.native['scan']['status'] = 'cancelled'
        self.assertEqual(self.run_check()[0], 1)

    def test_accuracy_requires_images_for_empty_ocr_too(self):
        self.native['config']['mode'] = 'accuracy'
        self.assertEqual(self.run_check()[0], 1)
        (self.bundle / 'input.png').write_bytes(b'fixture-image')
        self.native['frames'][0]['ocrInputFile'] = 'input.png'
        code, report = self.run_check()
        self.assertEqual(code, 0, report)
        self.assertEqual(report['layers']['performance'], 'WARN')

    def test_unsafe_image_is_rejected(self):
        self.native['config']['mode'] = 'accuracy'
        self.native['frames'][0]['ocrInputFile'] = '../truth.json'
        self.assertEqual(self.run_check()[0], 1)

    def test_malformed_frame_is_a_report_not_a_crash(self):
        self.native['frames'][0]['observations'] = 'bad'
        self.assertEqual(self.run_check()[0], 1)

    def test_reversed_timestamps_fail(self):
        self.native['frames'].append(dict(self.native['frames'][0], index=1, timestampSeconds=-1))
        self.assertEqual(self.run_check()[0], 1)

    def test_corpus_prevents_vial_leakage(self):
        self.run_check()
        manifest = self.root / 'corpus.json'
        cases = [{'bundle': 'scan', 'truth': 'truth.json', 'physicalVial': 'tube1', 'split': 'development'}]
        manifest.write_text(json.dumps({'cases': cases}))
        result = subprocess.run([sys.executable, str(COMMAND), 'corpus', str(manifest)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout)['counts']['correct'], 1)
        cases.append(dict(cases[0], split='holdout'))
        manifest.write_text(json.dumps({'cases': cases}))
        result = subprocess.run([sys.executable, str(COMMAND), 'corpus', str(manifest)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('both development and holdout', result.stderr)
