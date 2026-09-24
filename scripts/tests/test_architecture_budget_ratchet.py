from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "scripts" / "validate-architecture-hardening.py"


def load_validator_module():
    spec = importlib.util.spec_from_file_location("validate_architecture_hardening", VALIDATOR)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


validator = load_validator_module()


class ArchitectureBudgetRatchetTests(unittest.TestCase):
    def test_requires_budget_tightening_when_metric_improves(self) -> None:
        metrics = {"platform": {"gameplay_override_count": 100}}
        budget = {"limits": {"platform.gameplay_override_count": 116}, "ratchet_limits": ["platform.gameplay_override_count"]}
        previous = {"limits": {"platform.gameplay_override_count": 116}, "ratchet_limits": ["platform.gameplay_override_count"]}

        errors = validator.ratchet_errors(metrics, budget, previous)

        self.assertEqual(1, len(errors))
        self.assertIn('must be tightened from 116 to 100', errors[0])

    def test_rejects_budget_increase(self) -> None:
        metrics = {"platform": {"gameplay_override_count": 116}}
        budget = {"limits": {"platform.gameplay_override_count": 120}, "ratchet_limits": ["platform.gameplay_override_count"]}
        previous = {"limits": {"platform.gameplay_override_count": 116}, "ratchet_limits": ["platform.gameplay_override_count"]}

        errors = validator.ratchet_errors(metrics, budget, previous)

        self.assertEqual(1, len(errors))
        self.assertIn('cannot increase budget from 116 to 120', errors[0])

    def test_accepts_already_ratcheted_budget(self) -> None:
        metrics = {"platform": {"gameplay_override_count": 100}}
        budget = {"limits": {"platform.gameplay_override_count": 100}, "ratchet_limits": ["platform.gameplay_override_count"]}
        previous = {"limits": {"platform.gameplay_override_count": 116}, "ratchet_limits": ["platform.gameplay_override_count"]}

        self.assertEqual([], validator.ratchet_errors(metrics, budget, previous))


if __name__ == '__main__':
    unittest.main()
