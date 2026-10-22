#!/usr/bin/env python3
"""Run the maintained Minecraft 1.21.11 NeoForge parity guards as one CI gate."""
from __future__ import annotations

from pathlib import Path

from checks.suite_runner import run_script_suite

ROOT = Path(__file__).resolve().parents[1]
CHECK_ROOT = ROOT / "scripts/checks/parity_1.21.11"
CHECKS = (
    ("gate networking/UI", CHECK_ROOT / "gates.py"),
    ("robotics", CHECK_ROOT / "robotics.py"),
    ("fluid rendering", CHECK_ROOT / "fluid_render.py"),
    ("world/state compatibility", CHECK_ROOT / "world_state.py"),
    ("platform interop/builders/rendering", CHECK_ROOT / "platform_interop.py"),
)


def main() -> int:
    return run_script_suite(
        root=ROOT,
        suite_name="1.21.11 parity validation",
        step_prefix="1.21.11 parity",
        checks=CHECKS,
    )


if __name__ == "__main__":
    raise SystemExit(main())
