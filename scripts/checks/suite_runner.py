#!/usr/bin/env python3
"""Small shared runner for groups of independent Python validation scripts."""
from __future__ import annotations

from collections.abc import Sequence
from pathlib import Path
import subprocess
import sys

Check = tuple[str, Path]


def run_script_suite(
    *,
    root: Path,
    suite_name: str,
    checks: Sequence[Check],
    step_prefix: str | None = None,
) -> int:
    """Run every check, report all failing groups, and keep each check process-isolated."""
    failures: list[str] = []
    prefix = step_prefix or suite_name

    for label, script in checks:
        if not script.is_file():
            failures.append(f"{label}: missing {script.relative_to(root)}")
            continue
        print(f"==> {prefix}: {label}", flush=True)
        completed = subprocess.run([sys.executable, str(script)], cwd=root, check=False)
        if completed.returncode != 0:
            failures.append(f"{label}: exit {completed.returncode}")

    if failures:
        print(f"{suite_name} FAILED:")
        for failure in failures:
            print(f" - {failure}")
        return 1

    print(f"{suite_name} OK: {len(checks)} check groups")
    return 0
