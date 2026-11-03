"""Real-game trace collector: input isolation, strict schema and 7-target comparison."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("system_trace_parity", ROOT / "scripts/ci-system-trace-parity.py")
trace = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(trace)


class SystemTraceParityTests(unittest.TestCase):
    def setUp(self):
        self.samples = {
            "sorted_network_recovery": {"preferred": "5", "fallback": "7", "drops": "0", "inFlight": "false"},
            "enriched_item_pipeline": {"count": "1", "damage": "117", "sameData": "true"},
            "isolated_fluid_pipelines": {"waterDestination": "1000", "lavaDestination": "1000"},
            "mixed_factory_recovery": {"oakDelivery": "8", "spruceDelivery": "4",
                                       "waterDestination": "1000", "waterSource": "0",
                                       "inFlight": "false", "waterInPipes": "0"},
        }
        self.targets = ["1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge"]

    def line(self, name, fields):
        body = "|".join(f"{k}={v}" for k, v in fields.items())
        return f"[Server thread/INFO] [buildcraftlib]: BCCE_SYSTEM_TRACE|{name}|{body}"

    def make_dataset(self, root, target, scenarios=None):
        folder = root / "build" / "test-traces"
        folder.mkdir(parents=True, exist_ok=True)
        (folder / f"{target}.trace.json").write_text(json.dumps({"schema": 1, "target": target,
            "scenarios": scenarios or self.samples}), encoding="utf-8")

    def test_extracts_each_live_world_observation(self):
        text = "\n".join(["GameTest started", *[self.line(k, v) for k, v in self.samples.items()]])
        self.assertEqual(trace.parse_log(text), self.samples)

    def test_does_not_accept_startup_messages_as_a_completed_game_test(self):
        with self.assertRaisesRegex(ValueError, "Missing completed"):
            trace.parse_log("GameTest server started and created atlas")

    def test_refuses_duplicate_scenarios(self):
        text = "\n".join([self.line(k, v) for k, v in self.samples.items()] +
                         [self.line("enriched_item_pipeline", self.samples["enriched_item_pipeline"])])
        with self.assertRaisesRegex(ValueError, "Duplicate observation"):
            trace.parse_log(text)

    def test_refuses_repeated_dimensions(self):
        with self.assertRaisesRegex(ValueError, "duplicated"):
            trace.parse_observation("BCCE_SYSTEM_TRACE|pipe|count=1|count=2")

    def test_valid_real_log_can_be_written_as_target_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            log = d / "log"
            log.write_text("\n".join(self.line(k, v) for k, v in self.samples.items()))
            target = self.targets[0]
            output = d / "results" / f"{target}.trace.json"
            trace.extract(target, log, output, self.targets)
            self.assertEqual(json.loads(output.read_text())["scenarios"], self.samples)

    def test_compares_all_targets_and_rejects_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            for target in self.targets:
                self.make_dataset(d, target)
            trace.compare(d, self.targets)
            bad = {k: dict(v) for k, v in self.samples.items()}
            bad["enriched_item_pipeline"]["damage"] = "116"
            self.make_dataset(d, self.targets[2], bad)
            with self.assertRaisesRegex(ValueError, "damage"):
                trace.compare(d, self.targets)

    def test_mixed_factory_water_drift_is_detected_between_versions(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            for target in self.targets:
                self.make_dataset(d, target)
            altered = {key: dict(value) for key, value in self.samples.items()}
            altered["mixed_factory_recovery"]["waterDestination"] = "999"
            self.make_dataset(d, self.targets[1], altered)
            with self.assertRaisesRegex(ValueError, "mixed_factory_recovery / waterDestination"):
                trace.compare(d, self.targets)

    def test_missing_or_extra_target_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            for target in self.targets[:-1]:
                self.make_dataset(d, target)
            with self.assertRaisesRegex(ValueError, "Missing per-target"):
                trace.compare(d, self.targets)
            self.make_dataset(d, self.targets[-1])
            payload = d / "extra" / "1.19.2-forge.trace.json"
            payload.parent.mkdir()
            payload.write_text(json.dumps({"schema": 1, "target": self.targets[0], "scenarios": self.samples}))
            with self.assertRaisesRegex(ValueError, "duplicate"):
                trace.compare(d, self.targets)

    def test_configured_targets_match_real_manifest(self):
        targets = trace.configured_targets(ROOT / "build-config/targets.properties")
        self.assertIn("26.3-neoforge", targets)
        self.assertIn("1.19.2-forge", targets)
        self.assertEqual(len(targets), 7)


if __name__ == "__main__":
    unittest.main()
