#!/usr/bin/env python3
"""Run the maintained gameplay, rendering, performance and polish regression guards."""
from __future__ import annotations

from pathlib import Path

from checks.suite_runner import run_script_suite

ROOT = Path(__file__).resolve().parents[1]
CHECK_ROOT = ROOT / "scripts/checks/regressions"
CHECKS = (
    ("critical correctness", CHECK_ROOT / "critical.py"),
    ("runtime", CHECK_ROOT / "runtime.py"),
    ("machine behavior", CHECK_ROOT / "machines.py"),
    ("automation", CHECK_ROOT / "automation.py"),
    ("gameplay", CHECK_ROOT / "gameplay.py"),
    ("integrity", CHECK_ROOT / "integrity.py"),
    ("systems", CHECK_ROOT / "systems.py"),
    ("world/transport", CHECK_ROOT / "world_transport.py"),
    ("performance/rendering", CHECK_ROOT / "performance.py"),
    ("content/UI", CHECK_ROOT / "content.py"),
    ("render/Jade", CHECK_ROOT / "render_jade.py"),
    ("sound feedback", CHECK_ROOT / "sound_feedback.py"),
    ("polish", CHECK_ROOT / "polish.py"),
)


def main() -> int:
    return run_script_suite(
        root=ROOT,
        suite_name="Regression validation",
        step_prefix="Regression guards",
        checks=CHECKS,
    )


if __name__ == "__main__":
    raise SystemExit(main())
