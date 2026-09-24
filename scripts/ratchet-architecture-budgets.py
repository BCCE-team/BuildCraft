#!/usr/bin/env python3
"""Tighten architecture budgets to the current measured values."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "scripts" / "validate-architecture-hardening.py"


def load_validator_module():
    spec = importlib.util.spec_from_file_location("validate_architecture_hardening", VALIDATOR)
    if spec is None or spec.loader is None:
        raise SystemExit(f"Cannot load validator module from {VALIDATOR}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    validator = load_validator_module()
    budget = validator.load_budget_file()
    metrics = validator.current_metrics()
    limits = budget.get("limits", {})
    if not isinstance(limits, dict):
        raise SystemExit("architecture budgets: limits must be an object")

    ratchets = validator.ratchet_metric_paths(budget)
    updated: dict[str, int] = dict(limits)
    changed = False
    for metric_path in sorted(ratchets):
        maximum = limits[metric_path]
        if not isinstance(maximum, int):
            raise SystemExit(f"architecture budget {metric_path!r} must be an integer")
        current = validator.get_metric(metrics, metric_path)
        updated_value = current if current < maximum else maximum
        updated[metric_path] = updated_value
        if updated_value != maximum:
            changed = True

    budget["limits"] = updated
    validator.BUDGET_FILE.write_text(
        json.dumps(budget, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
        newline="",
    )
    status = "updated" if changed else "already tight"
    print(f"Architecture budgets ratchet: {status}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
