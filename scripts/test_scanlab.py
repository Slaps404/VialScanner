#!/usr/bin/env python3
"""Small fixture tests for scripts/scanlab.

These are subprocess tests on purpose.  They exercise the same command and
exit codes a person running a Pixel/iPhone collection will use.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
COMMAND = ROOT / "scripts" / "scanlab"
FIXTURES = ROOT / "scripts" / "fixtures"


class ScanLabCommandTests(unittest.TestCase):
    def run_check(self, name: str | Path, *extra: str) -> subprocess.CompletedProcess[str]:
        bundle = Path(name)
        if not bundle.is_absolute():
            bundle = FIXTURES / bundle
        return subprocess.run(
            [sys.executable, str(COMMAND), "check", str(bundle), *extra],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    def test_known_bundle_passes(self) -> None:
        result = self.run_check("good-known")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("ScanLab PASS", result.stdout)
        self.assertIn("matching     OK", result.stdout)

    def test_catalogue_bundle_requires_and_checks_full_rotation_fields(self) -> None:
        result = self.run_check("good-catalogue", "--json")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        payload = json.loads(result.stdout)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["stats"]["frames"], 4)
        self.assertEqual(payload["stats"]["ocrFrames"], 4)

    def test_expected_mismatch_is_red_and_routed(self) -> None:
        result = self.run_check("bad-mismatch")
        self.assertEqual(result.returncode, 1)
        self.assertIn("ERROR [extraction]", result.stdout)
        self.assertIn("scanner miss", result.stdout)

    def test_non_label_correction_is_feedback_not_scanner_miss(self) -> None:
        result = self.run_check("good-manual-correction")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("INFO [feedback]", result.stdout)
        self.assertIn("not counted as scanner miss", result.stdout)

    def test_missing_ground_truth_is_red(self) -> None:
        result = self.run_check("bad-no-ground-truth")
        self.assertEqual(result.returncode, 1)
        self.assertIn("summary.groundTruth must be an object", result.stdout)
        self.assertIn("schema       FAIL", result.stdout)

    def test_missing_summary_is_red(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 1)
        self.assertIn("missing summary.json", result.stdout)

    def test_known_bundle_without_required_fields_checks_declared_values(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            bundle = Path(directory) / "known"
            shutil.copytree(FIXTURES / "good-known", bundle)
            summary_path = bundle / "summary.json"
            summary = json.loads(summary_path.read_text())
            summary["groundTruth"].pop("requiredFields")
            summary_path.write_text(json.dumps(summary))
            result = self.run_check(bundle)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_catalogue_rotation_must_be_declared_required(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            bundle = Path(directory) / "catalogue"
            shutil.copytree(FIXTURES / "good-catalogue", bundle)
            summary_path = bundle / "summary.json"
            summary = json.loads(summary_path.read_text())
            summary["capture"]["rotation"]["required"] = False
            summary_path.write_text(json.dumps(summary))
            result = self.run_check(bundle)
        self.assertEqual(result.returncode, 1)
        self.assertIn("rotation", result.stdout)

    def test_absolute_artifact_path_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            bundle = Path(directory) / "absolute"
            shutil.copytree(FIXTURES / "good-known", bundle)
            summary_path = bundle / "summary.json"
            summary = json.loads(summary_path.read_text())
            summary["artifacts"]["trace"] = str((bundle / "trace.jsonl").resolve())
            summary_path.write_text(json.dumps(summary))
            result = self.run_check(bundle)
        self.assertEqual(result.returncode, 1)
        self.assertIn("relative path", result.stdout)

    def test_first_duplicate_correction_is_the_one_used(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            bundle = Path(directory) / "corrections"
            shutil.copytree(FIXTURES / "good-manual-correction", bundle)
            summary_path = bundle / "summary.json"
            summary = json.loads(summary_path.read_text())
            correction = summary["actual"].pop("corrections")["lot"]
            correction["field"] = "lot"
            summary["actual"]["corrections"] = [
                correction,
                {**correction, "visibleOnLabel": True},
            ]
            summary_path.write_text(json.dumps(summary))
            result = self.run_check(bundle)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("multiple corrections recorded for lot", result.stdout)
        self.assertIn("not counted as scanner miss", result.stdout)

    def test_trace_with_no_usable_frames_is_red(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            bundle = Path(directory) / "no-usable"
            shutil.copytree(FIXTURES / "good-known", bundle)
            summary_path = bundle / "summary.json"
            summary = json.loads(summary_path.read_text())
            summary["capture"]["usableFrames"] = 0
            summary["capture"]["ocrFrames"] = 0
            summary_path.write_text(json.dumps(summary))
            trace_path = bundle / "trace.jsonl"
            events = [json.loads(line) for line in trace_path.read_text().splitlines()]
            for event in events:
                event["usable"] = False
                event["ocr"]["ran"] = False
            trace_path.write_text("\n".join(json.dumps(event) for event in events) + "\n")
            result = self.run_check(bundle)
        self.assertEqual(result.returncode, 1)
        self.assertIn("no usable frames", result.stdout)
        self.assertIn("capture      FAIL", result.stdout)

    def test_pull_pixel_writes_manifest_after_successful_atomic_pull(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            log = root / "adb.log"
            fake_adb = bin_dir / "adb"
            fake_adb.write_text(
                "#!/usr/bin/env python3\n"
                "import pathlib, sys\n"
                f"log = pathlib.Path({str(log)!r})\n"
                "with log.open('a') as handle: handle.write(' '.join(sys.argv[1:]) + '\\n')\n"
                "args = sys.argv[1:]\n"
                "if args == ['devices', '-l']:\n"
                "    print('List of devices attached')\n"
                "    print('pixel-1\\tdevice product:foo')\n"
                "elif 'shell' in args:\n"
                "    print('Thermal Status: 0')\n"
                "elif 'pull' in args:\n"
                "    pathlib.Path(args[-1]).mkdir(parents=True, exist_ok=True)\n"
                "    pathlib.Path(args[-1], 'bundle.json').write_text('{}')\n"
                "else:\n"
                "    raise SystemExit(2)\n"
            )
            fake_adb.chmod(fake_adb.stat().st_mode | 0o111)
            destination = root / "pulled"
            environment = os.environ.copy()
            environment["PATH"] = str(bin_dir) + os.pathsep + environment.get("PATH", "")
            result = subprocess.run(
                [sys.executable, str(COMMAND), "pull-pixel", str(destination)],
                cwd=ROOT,
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            manifest = json.loads((destination / "pull.json").read_text())
            self.assertEqual(manifest["device"]["serial"], "pixel-1")
            self.assertEqual(manifest["device"]["thermalStatus"], 0)
            self.assertTrue((destination / "bundle.json").exists())
            self.assertFalse(any(root.glob(".pulled.partial-*")))
            pull_log = log.read_text()
            self.assertIn("pull", pull_log)
            self.assertIn("/sdcard/Android/media/org.lji.vialscanner/debug-bundles", pull_log)

    def test_pull_pixel_removes_partial_data_when_adb_pull_fails(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            fake_adb = bin_dir / "adb"
            fake_adb.write_text(
                "#!/usr/bin/env python3\n"
                "import pathlib, sys\n"
                "args = sys.argv[1:]\n"
                "if args == ['devices', '-l']:\n"
                "    print('List of devices attached')\n"
                "    print('pixel-1\\tdevice')\n"
                "elif 'shell' in args:\n"
                "    print('Thermal Status: 0')\n"
                "elif 'pull' in args:\n"
                "    pathlib.Path(args[-1], 'partial.txt').write_text('incomplete')\n"
                "    raise SystemExit(7)\n"
                "else:\n"
                "    raise SystemExit(2)\n"
            )
            fake_adb.chmod(fake_adb.stat().st_mode | 0o111)
            destination = root / "pulled"
            environment = os.environ.copy()
            environment["PATH"] = str(bin_dir) + os.pathsep + environment.get("PATH", "")
            result = subprocess.run(
                [sys.executable, str(COMMAND), "pull-pixel", str(destination)],
                cwd=ROOT,
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
        self.assertEqual(result.returncode, 1)
        self.assertIn("adb pull failed", result.stderr)
        self.assertFalse(destination.exists())
        self.assertFalse(any(root.glob(".pulled.partial-*")))


if __name__ == "__main__":
    unittest.main()
