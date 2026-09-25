#!/usr/bin/env python3
"""Validate the machine-readable API v2 coverage matrix for the Fabric critical path."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "build-config" / "api-v2-coverage.json"
REQUIRED_CRITICAL = {
    "pipe_component_persistence",
    "pipe_component_sync",
    "pipe_execution_neighbours",
    "pipe_component_lifecycle",
    "pipe_component_ports",
    "item_transit_interception",
    "fluid_ingress_interception",
    "mj_network_interception",
}
VALID_STATUS = {"covered", "deferred"}


def main() -> int:
    errors: list[str] = []
    try:
        data = json.loads(MATRIX.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"API v2 coverage FAILED: {exc}")
        return 1
    if data.get("schema_version") != 1:
        errors.append("unsupported or missing schema_version")
    domains = data.get("domains")
    if not isinstance(domains, dict):
        errors.append("domains must be an object")
        domains = {}

    missing = sorted(REQUIRED_CRITICAL - set(domains))
    if missing:
        errors.append("missing critical coverage domains: " + ", ".join(missing))

    for name, entry in sorted(domains.items()):
        if not isinstance(entry, dict):
            errors.append(f"{name}: entry must be an object")
            continue
        status = entry.get("status")
        if status not in VALID_STATUS:
            errors.append(f"{name}: invalid status {status!r}")
            continue
        if name in REQUIRED_CRITICAL and status != "covered":
            errors.append(f"{name}: Fabric-critical API hole is not covered")
        if status == "covered":
            evidence = entry.get("evidence")
            if not isinstance(evidence, list) or not evidence:
                errors.append(f"{name}: covered domain requires evidence files")
                continue
            for relative in evidence:
                if not isinstance(relative, str) or not (ROOT / relative).is_file():
                    errors.append(f"{name}: missing evidence file {relative!r}")
        elif not entry.get("target_stage"):
            errors.append(f"{name}: deferred domain requires target_stage")

    if errors:
        print("API v2 coverage FAILED:")
        for error in errors:
            print(" - " + error)
        return 1
    covered = sum(1 for entry in domains.values() if entry.get("status") == "covered")
    deferred = sum(1 for entry in domains.values() if entry.get("status") == "deferred")
    print(f"API v2 coverage OK: {covered} covered domain(s), {deferred} explicitly deferred; Fabric-critical holes = 0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
