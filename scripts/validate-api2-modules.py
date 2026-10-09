#!/usr/bin/env python3
"""Run all module-level API2 migration contract validators as one stable CI gate."""
from __future__ import annotations

from pathlib import Path

from checks.suite_runner import run_script_suite

ROOT = Path(__file__).resolve().parents[1]
CHECKS = (
    ("core/misc", ROOT / "scripts/validate-core-misc-api2.py"),
    ("energy/engines/machines", ROOT / "scripts/validate-energy-api2.py"),
    ("facades/lists/map", ROOT / "scripts/validate-facades-lists-map-api2.py"),
    ("robots/boards/requests", ROOT / "scripts/validate-robots-api2.py"),
    ("schematics/builders", ROOT / "scripts/validate-schematics-api2.py"),
    ("signals/wires/stripes/automation", ROOT / "scripts/validate-signals-automation-api2.py"),
    ("statements/gates/filler", ROOT / "scripts/validate-statements-api2.py"),
    ("transport", ROOT / "scripts/validate-transport-api2.py"),
)


def main() -> int:
    return run_script_suite(
        root=ROOT,
        suite_name="API2 module contract validation",
        step_prefix="API2 module contracts",
        checks=CHECKS,
    )


if __name__ == "__main__":
    raise SystemExit(main())
