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

    def test_catalogue_completeness_uses_verified_fields_not_rotation(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            bundle = Path(directory) / "catalogue"
            shutil.copytree(FIXTURES / "good-catalogue", bundle)
            summary_path = bundle / "summary.json"
            summary = json.loads(summary_path.read_text())
            summary["capture"]["rotation"]["required"] = False
            summary_path.write_text(json.dumps(summary))
            result = self.run_check(bundle)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

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


class ScanLabTranslateTests(unittest.TestCase):
    """Native phone output is only reviewable after translation."""

    def run_scanlab(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(COMMAND), *args],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    def test_ios_sidecar_translates_into_a_passing_bundle(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            out = Path(directory) / "translated"
            result = self.run_scanlab(
                "translate", str(FIXTURES / "ios-known" / "scan-0001.json"), "--out", str(out)
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            summary = json.loads((out / "summary.json").read_text())
            self.assertEqual(summary["schema"], "scanlab.bundle")
            self.assertEqual(summary["platform"], "ios")
            self.assertEqual(summary["groundTruth"]["mode"], "known_vial")
            self.assertEqual(summary["groundTruth"]["fields"]["lot"], "B411234")
            # Counters are derived from the translated trace so the integrity
            # check compares like with like.
            self.assertEqual(summary["capture"]["framesSeen"], 3)
            self.assertEqual(summary["capture"]["usableFrames"], 2)
            self.assertEqual(summary["capture"]["ocrFrames"], 2)
            check = self.run_scanlab("check", str(out))
            self.assertEqual(check.returncode, 0, check.stdout + check.stderr)
            self.assertIn("ScanLab PASS", check.stdout)

    def test_translated_ios_miss_routes_to_extraction(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            out = Path(directory) / "translated"
            self.run_scanlab("translate", str(FIXTURES / "ios-miss" / "scan-0002.json"), "--out", str(out))
            check = self.run_scanlab("check", str(out))
        self.assertEqual(check.returncode, 1)
        self.assertIn("ERROR [extraction]", check.stdout)
        self.assertIn("lot: expected 'B411234'", check.stdout)

    def test_pixel_bundle_translates_using_the_operator_note(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            out = Path(directory) / "translated"
            result = self.run_scanlab("translate", str(FIXTURES / "pixel-native"), "--out", str(out))
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            summary = json.loads((out / "summary.json").read_text())
            self.assertEqual(summary["platform"], "android")
            self.assertEqual(summary["groundTruth"]["fields"]["catalog"], "910801")
            check = self.run_scanlab("check", str(out))
            self.assertEqual(check.returncode, 0, check.stdout + check.stderr)

    def test_native_pixel_check_still_reads_the_phone_format(self) -> None:
        """The native reader and the translator share one frame-event builder."""
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            truth = Path(directory) / "truth.json"
            truth.write_text(json.dumps({
                "mode": "known_vial",
                "fields": {"catalog": "910801", "lot": "B411234"},
                "requiredFields": ["catalog", "lot"],
                "verdict": "confident",
            }))
            result = self.run_scanlab(
                "check", str(FIXTURES / "pixel-native"), "--pixel", "--truth", str(truth), "--json"
            )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        payload = json.loads(result.stdout)
        self.assertEqual(payload["stats"]["frames"], 3)
        self.assertEqual(payload["stats"]["usableFrames"], 2)
        self.assertEqual(payload["stats"]["ocrFrames"], 2)
        self.assertIn("ocr", payload["stats"]["stages"])

    def test_translate_refuses_an_unknown_schema(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            source = Path(directory) / "other.json"
            source.write_text(json.dumps({"schema": "something.else", "schemaVersion": 1}))
            result = self.run_scanlab("translate", str(source), "--out", str(Path(directory) / "out"))
        self.assertEqual(result.returncode, 1)
        self.assertIn("not a recognized", result.stderr)

    def test_translate_refuses_to_overwrite(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            out = Path(directory) / "translated"
            out.mkdir()
            result = self.run_scanlab(
                "translate", str(FIXTURES / "ios-known" / "scan-0001.json"), "--out", str(out)
            )
        self.assertEqual(result.returncode, 1)
        self.assertIn("already exists", result.stderr)


class ScanLabReviewTests(unittest.TestCase):
    """One command over a tray of scans, whatever format they arrived in."""

    def run_review(self, root: Path, *extra: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(COMMAND), "review", str(root), *extra],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    def mixed_tray(self, directory: str) -> Path:
        root = Path(directory) / "tray"
        root.mkdir()
        shutil.copytree(FIXTURES / "good-known", root / "canonical-known")
        shutil.copytree(FIXTURES / "pixel-native", root / "pixel-known")
        for name in ("ios-known", "ios-miss", "ios-unannotated"):
            shutil.copytree(FIXTURES / name, root / name)
        return root

    def test_review_reports_every_scan_and_fails_on_a_miss(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            result = self.run_review(self.mixed_tray(directory))
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("5 scans", result.stdout)
        self.assertIn("ios-fixture-known", result.stdout)
        self.assertIn("ios-fixture-miss", result.stdout)
        self.assertIn("pixel-fixture-known", result.stdout)
        # An un-annotated scan is not a scanner failure; it is a missing note.
        self.assertIn("NOTE?", result.stdout)
        self.assertIn("1 unannotated", result.stdout)
        self.assertIn("[extraction] field-mismatch", result.stdout)

    def test_review_names_the_failing_layer_not_a_warning(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            root = Path(directory) / "tray"
            root.mkdir()
            shutil.copytree(FIXTURES / "bad-mismatch", root / "canonical-mismatch")
            result = self.run_review(root, "--json")
        payload = json.loads(result.stdout)
        self.assertEqual(payload["scans"][0]["layer"], "extraction")

    def test_review_json_ranks_failure_modes(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            result = self.run_review(self.mixed_tray(directory), "--json")
        self.assertEqual(result.returncode, 1)
        payload = json.loads(result.stdout)
        self.assertFalse(payload["ok"])
        self.assertEqual(payload["counts"]["correct"], 3)
        self.assertEqual(payload["counts"]["unannotated"], 1)
        self.assertEqual(payload["counts"]["wrong_result"], 1)
        top = payload["failureModes"][0]
        self.assertEqual(top["layer"], "extraction")
        self.assertEqual(top["code"], "field-mismatch")
        self.assertEqual(top["count"], 1)
        notes = {scan["bundleId"]: scan["note"] for scan in payload["scans"]}
        self.assertIn("lot never made it into the result", notes["ios-fixture-miss"])

    def test_review_only_fail_hides_passing_scans(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            result = self.run_review(self.mixed_tray(directory), "--only-fail")
        self.assertEqual(result.returncode, 1)
        self.assertIn("ios-fixture-miss", result.stdout)
        self.assertNotIn("ios-fixture-known", result.stdout)

    def test_review_of_only_good_scans_is_green(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            root = Path(directory) / "tray"
            root.mkdir()
            shutil.copytree(FIXTURES / "ios-known", root / "ios-known")
            result = self.run_review(root)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("1 scans", result.stdout)

    def test_review_needs_at_least_one_bundle(self) -> None:
        with tempfile.TemporaryDirectory(prefix="scanlab-test-") as directory:
            result = self.run_review(Path(directory))
        self.assertEqual(result.returncode, 1)
        self.assertIn("no debug bundles", result.stderr)


if __name__ == "__main__":
    unittest.main()
