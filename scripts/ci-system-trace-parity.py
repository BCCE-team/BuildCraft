#!/usr/bin/env python3
"""Extract real, game-observed system traces and fail on cross-target semantic drift.

No test is inferred from a successful process exit: a scenario must emit a
completed-world observation after its assertions pass. Missing observations,
duplicates and unexpected targets fail closed.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRACE_PREFIX = "BCCE_SYSTEM_TRACE|"
EXPECTED_SCENARIOS = frozenset({
    "sorted_network_recovery",
    "enriched_item_pipeline",
    "isolated_fluid_pipelines",
    "mixed_factory_recovery",
})
TARGET = re.compile(r"^[0-9][0-9.a-z-]*-(?:forge|neoforge)$")
SCENARIO = re.compile(r"^[a-z][a-z0-9_]*$")
DIMENSION = re.compile(r"^[a-z][a-zA-Z0-9_]*$")
VALUE = re.compile(r"^[a-zA-Z0-9_.:-]+$")


def configured_targets(properties: Path) -> list[str]:
    values = [line.split("=", 1)[1] for line in properties.read_text(encoding="utf-8").splitlines()
              if line.startswith("targets=")]
    if len(values) != 1:
        raise ValueError(f"Expected exactly one targets= in {properties}")
    targets = values[0].split(",")
    if not targets or len(set(targets)) != len(targets) or not all(TARGET.fullmatch(x) for x in targets):
        raise ValueError("Invalid/duplicate target names")
    return targets


def parse_observation(text: str) -> tuple[str, dict[str, str]]:
    if not text.startswith(TRACE_PREFIX):
        raise ValueError("Not a system GameTest trace")
    parts = text[len(TRACE_PREFIX):].split("|")
    if not SCENARIO.fullmatch(parts[0]) or len(parts) < 2:
        raise ValueError(f"Invalid trace scenario/dimensions: {text!r}")
    values: dict[str, str] = {}
    for part in parts[1:]:
        if part.count("=") != 1:
            raise ValueError(f"Invalid trace dimension {part!r}")
        name, value = part.split("=", 1)
        if not DIMENSION.fullmatch(name) or not VALUE.fullmatch(value) or name in values:
            raise ValueError(f"Invalid or duplicated trace dimension {part!r}")
        values[name] = value
    return parts[0], values


def parse_log(raw: str, *, expected: frozenset[str] = EXPECTED_SCENARIOS) -> dict[str, dict[str, str]]:
    observations: dict[str, dict[str, str]] = {}
    for line in raw.splitlines():
        if TRACE_PREFIX not in line:
            continue
        # Minecraft log prefix: [Server thread/INFO] [buildcraft]: BCCE_SYSTEM_TRACE|...
        trace = line[line.index(TRACE_PREFIX):].split()[0]
        scenario, values = parse_observation(trace)
        if scenario not in expected:
            continue  # Additional future traces should not break the baseline comparison.
        if scenario in observations:
            raise ValueError(f"Duplicate observation for {scenario}")
        observations[scenario] = values
    missing = expected - observations.keys()
    if missing:
        raise ValueError(f"Missing completed GameTest traces: {', '.join(sorted(missing))}")
    return observations


def extract(target: str, log: Path, output: Path, configured: list[str]) -> None:
    if target not in configured:
        raise ValueError(f"Target {target} is not configured")
    observations = parse_log(log.read_text(encoding="utf-8", errors="replace"))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"schema": 1, "target": target,
                                  "scenarios": observations}, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(f"Collected {len(observations)} completed GameTest system traces for {target}")


def compare(input_root: Path, configured: list[str]) -> None:
    files = sorted(input_root.rglob("*.trace.json"))
    datasets: dict[str, dict[str, dict[str, str]]] = {}
    for path in files:
        payload = json.loads(path.read_text(encoding="utf-8"))
        if payload.get("schema") != 1 or not isinstance(payload.get("scenarios"), dict):
            raise ValueError(f"Unexpected trace format in {path}")
        target = payload.get("target")
        if target not in configured or target in datasets or path.name != f"{target}.trace.json":
            raise ValueError(f"Unexpected, duplicate or misnamed target {target!r} in {path}")
        if set(payload["scenarios"]) != EXPECTED_SCENARIOS:
            raise ValueError(f"Target {target} did not run all cross-target scenarios")
        for scenario, dimensions in payload["scenarios"].items():
            if not isinstance(dimensions, dict) or not dimensions:
                raise ValueError(f"Target {target}/{scenario} has no dimensions")
            # Validate input just as strictly as the original logged observation.
            parse_observation(TRACE_PREFIX + scenario + "|" + "|".join(
                f"{key}={value}" for key, value in sorted(dimensions.items())))
        datasets[target] = payload["scenarios"]
    if set(datasets) != set(configured):
        absent = sorted(set(configured) - set(datasets))
        raise ValueError(f"Missing per-target results: {absent}")
    baseline = configured[0]
    reference = datasets[baseline]
    mismatches = []
    for target in configured[1:]:
        for scenario in sorted(EXPECTED_SCENARIOS):
            actual, expected = datasets[target][scenario], reference[scenario]
            for dimension in sorted(set(actual) | set(expected)):
                if actual.get(dimension) != expected.get(dimension):
                    mismatches.append(f"{target} / {scenario} / {dimension}: "
                                      f"expected {expected.get(dimension)!r} from {baseline}, "
                                      f"got {actual.get(dimension)!r}")
    if mismatches:
        raise ValueError("Cross-target gameplay parity regression:\n" + "\n".join(mismatches))
    print(f"PASS: {len(configured)} targets have equal live gameplay traces for "
          f"{len(EXPECTED_SCENARIOS)} end-to-end systems")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--targets-properties", type=Path, default=ROOT / "build-config/targets.properties")
    sub = parser.add_subparsers(dest="mode", required=True)
    extract_cmd = sub.add_parser("extract")
    extract_cmd.add_argument("--target", required=True)
    extract_cmd.add_argument("--log", type=Path, required=True)
    extract_cmd.add_argument("--output", type=Path, required=True)
    compare_cmd = sub.add_parser("compare")
    compare_cmd.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    targets = configured_targets(args.targets_properties)
    if args.mode == "extract":
        extract(args.target, args.log, args.output, targets)
    else:
        compare(args.directory, targets)


if __name__ == "__main__":
    main()
