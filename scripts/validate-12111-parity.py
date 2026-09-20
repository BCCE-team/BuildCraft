#!/usr/bin/env python3
"""Run the maintained 1.21.11 NeoForge parity guards as one stable CI gate."""
from __future__ import annotations

from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CHECKS = (
    ("gate networking/UI", ROOT / "scripts/checks/parity_12111/gates.py"),
    ("robotics", ROOT / "scripts/checks/parity_12111/robotics.py"),
    ("fluid rendering", ROOT / "scripts/checks/parity_12111/fluid_render.py"),
    ("Zone Planner rendering", ROOT / "scripts/checks/parity_12111/zone_planner.py"),
    ("world/state compatibility", ROOT / "scripts/checks/parity_12111/world_state.py"),
    ("platform interop/builders/rendering", ROOT / "scripts/checks/parity_12111/platform_interop.py"),
)


def main() -> int:
    failures: list[str] = []
    for label, script in CHECKS:
        if not script.is_file():
            failures.append(f"{label}: missing {script.relative_to(ROOT)}")
            continue
        print(f"==> 1.21.11 parity: {label}", flush=True)
        completed = subprocess.run([sys.executable, str(script)], cwd=ROOT, check=False)
        if completed.returncode != 0:
            failures.append(f"{label}: exit {completed.returncode}")

    if failures:
        print("1.21.11 parity validation FAILED:")
        for failure in failures:
            print(f" - {failure}")
        return 1

    print(f"1.21.11 parity validation OK: {len(CHECKS)} check groups")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
