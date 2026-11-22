#!/usr/bin/env python3
"""Run focused Robot Processor regression groups from the repository root.

Each group is process-isolated, because JVM fixture compilation and versioned
source preprocessor tests may use environment-dependent caches.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SUITES = ROOT / 'scripts/tests/robot_processor'
GROUPS = (
    'discovery', 'execution', 'navigation', 'logistics', 'builder',
    'combat', 'charging', 'ui', 'compat',
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--group', action='append', choices=GROUPS,
                        help='Run this thematic group (repeat to select several)')
    parser.add_argument('--list', action='store_true', help='List available groups')
    parser.add_argument('--quiet', action='store_true', help='Suppress per-test names')
    args = parser.parse_args()
    if args.list:
        print('\n'.join(GROUPS))
        return 0
    selected = tuple(dict.fromkeys(args.group or GROUPS))
    failures = []
    for group in selected:
        path = SUITES/group
        print(f'==> Robot Processor tests: {group}', flush=True)
        cmd = (sys.executable, '-m', 'unittest', 'discover', '-s', str(path),
               '-p', 'test_*.py', '-q' if args.quiet else '-v')
        result = subprocess.run(cmd, cwd=ROOT, check=False)
        if result.returncode:
            failures.append(group)
    if failures:
        print('FAILED Robot Processor groups: ' + ', '.join(failures))
        return 1
    print(f'Robot Processor tests OK: {len(selected)} groups')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
