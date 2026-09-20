#!/usr/bin/env python3
"""Run the maintained gameplay/render/performance regression guards as one CI gate."""
from __future__ import annotations

from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CHECKS = (
    ("critical correctness", ROOT / "scripts/checks/regressions/critical.py"),
    ("runtime", ROOT / "scripts/checks/regressions/runtime.py"),
    ("machine behavior", ROOT / "scripts/checks/regressions/machines.py"),
    ("automation", ROOT / "scripts/checks/regressions/automation.py"),
    ("gameplay", ROOT / "scripts/checks/regressions/gameplay.py"),
    ("integrity", ROOT / "scripts/checks/regressions/integrity.py"),
    ("systems", ROOT / "scripts/checks/regressions/systems.py"),
    ("world/transport", ROOT / "scripts/checks/regressions/world_transport.py"),
    ("performance/rendering", ROOT / "scripts/checks/regressions/performance.py"),
    ("content/UI", ROOT / "scripts/checks/regressions/content.py"),
    ("render/Jade", ROOT / "scripts/checks/regressions/render_jade.py"),
    ("polish", ROOT / "scripts/checks/regressions/polish.py"),
)


def main() -> int:
    failures: list[str] = []
    for label, script in CHECKS:
        if not script.is_file():
            failures.append(f"{label}: missing {script.relative_to(ROOT)}")
            continue
        print(f"==> Regression guards: {label}", flush=True)
        completed = subprocess.run([sys.executable, str(script)], cwd=ROOT, check=False)
        if completed.returncode != 0:
            failures.append(f"{label}: exit {completed.returncode}")

    if failures:
        print("Regression validation FAILED:")
        for failure in failures:
            print(f" - {failure}")
        return 1

    print(f"Regression validation OK: {len(CHECKS)} check groups")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
