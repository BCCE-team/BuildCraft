#!/usr/bin/env python3
"""Stage and verify the exact production JARs that passed the per-target API gate.

The runtime matrix uploads these only after its GameTest/world-join/installed
server checks pass. Publication consumes these artifacts, not a second build.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import sys

from api_v2_abi import ROOT, candidate_api_version, load_baseline, sha256


def mod_version() -> str:
    return next(line.split('=', 1)[1] for line in (ROOT / 'build-config/common.properties').read_text(encoding='utf-8').splitlines()
                if line.startswith('common.mod.version='))


def jar_name(target: str) -> str:
    return f'BuildCraft-Community-Edition-{mod_version()}+{target.replace("-", "+")}.jar'


def validate_pair(target: str, jar: Path, report_path: Path, baseline: dict) -> None:
    if jar.name != jar_name(target) or not jar.is_file() or not report_path.is_file():
        raise ValueError(f'{target}: missing or incorrect production JAR / ABI report')
    report = json.loads(report_path.read_text(encoding='utf-8'))
    expected = {
        'target': target, 'status': 'passed', 'old_binary_consumer': 'passed',
        'baseline': baseline['release'], 'baseline_api': baseline['api'], 'candidate_api': candidate_api_version(),
        'baseline_sha256': baseline['sha256'][target], 'candidate_sha256': sha256(jar),
        'violations': [],
    }
    for key, value in expected.items():
        if report.get(key) != value:
            raise ValueError(f'{target}: invalid ABI report field {key}: {report.get(key)!r} != {value!r}')
    if not isinstance(report.get('baseline_exported_classes'), int) or report['baseline_exported_classes'] <= 0:
        raise ValueError(f'{target}: ABI report has no exported baseline classes')
    if not isinstance(report.get('candidate_exported_classes'), int) or report['candidate_exported_classes'] < report['baseline_exported_classes']:
        raise ValueError(f'{target}: ABI report has fewer exported candidate classes')


def stage(target: str, jar: Path, report: Path, directory: Path, baseline: dict) -> None:
    validate_pair(target, jar, report, baseline)
    directory.mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar, directory / jar.name)
    shutil.copy2(report, directory / f'api-abi-{target}.json')
    validate_pair(target, directory / jar.name, directory / f'api-abi-{target}.json', baseline)
    print(f'{target}: staged SHA-256 verified production JAR')


def verify_all(directory: Path, baseline: dict) -> None:
    expected = {jar_name(target) for target in baseline['sha256']}
    expected_reports = {f'api-abi-{target}.json' for target in baseline['sha256']}
    jars = sorted(p for p in directory.rglob('*.jar') if p.is_file())
    reports = sorted(p for p in directory.rglob('api-abi-*.json') if p.is_file())
    if len(jars) != len(expected) or {p.name for p in jars} != expected:
        raise ValueError(f'Expected exactly {len(expected)} unique production JARs; found {[p.name for p in jars]}')
    if len(reports) != len(expected_reports) or {p.name for p in reports} != expected_reports:
        raise ValueError(f'Expected exactly {len(expected_reports)} unique ABI reports; found {[p.name for p in reports]}')
    files = {p.name: p for p in jars + reports}
    for target in baseline['sha256']:
        validate_pair(target, files[jar_name(target)], files[f'api-abi-{target}.json'], baseline)
        print(f'{target}: verified')
    print(f'All {len(expected)} production targets passed the API compatibility gate')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    stage_parser = commands.add_parser('stage')
    stage_parser.add_argument('--target', required=True)
    stage_parser.add_argument('--jar', type=Path, required=True)
    stage_parser.add_argument('--report', type=Path, required=True)
    stage_parser.add_argument('--directory', type=Path, required=True)
    all_parser = commands.add_parser('verify-all')
    all_parser.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    try:
        baseline = load_baseline()
        if args.command == 'stage':
            stage(args.target, args.jar, args.report, args.directory, baseline)
        else:
            verify_all(args.directory, baseline)
        return 0
    except (OSError, ValueError, KeyError) as error:
        print(f'Production API artifacts FAILED: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
