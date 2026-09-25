#!/usr/bin/env python3
"""Fail when the effective API v2 source surface changes without an explicit baseline update."""
from __future__ import annotations

import json
import sys
from pathlib import Path

from api_v2_surface import ROOT, snapshot

BASELINE = ROOT / "build-config" / "api-v2-symbol-baseline.json"


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)


def main() -> int:
    if not BASELINE.is_file():
        fail(f"missing {BASELINE.relative_to(ROOT)}; run scripts/update-api-v2-symbol-baseline.py")
        return 1
    expected = json.loads(BASELINE.read_text(encoding="utf-8"))
    current = snapshot()
    errors: list[str] = []

    if expected.get("schema_version") != 1 or expected.get("kind") != "normalized_api_v2_source_tokens":
        errors.append("unsupported API v2 symbol baseline schema")
    expected_targets = expected.get("targets", {})
    current_targets = current.get("targets", {})
    for target in sorted(set(expected_targets) | set(current_targets)):
        if target not in expected_targets:
            errors.append(f"{target}: target has no API v2 baseline; refresh the baseline intentionally")
            continue
        if target not in current_targets:
            errors.append(f"{target}: baseline target disappeared")
            continue
        before = expected_targets[target].get("files", {})
        after = current_targets[target].get("files", {})
        for path in sorted(set(before) | set(after)):
            if path not in before:
                errors.append(f"{target}: API file added without baseline update: {path}")
            elif path not in after:
                errors.append(f"{target}: API file removed without baseline update: {path}")
            elif before[path].get("sha256") != after[path].get("sha256"):
                errors.append(f"{target}: API source surface changed without baseline update: {path}")

    if errors:
        print("API v2 symbol baseline FAILED:")
        for error in errors[:50]:
            print(f" - {error}")
        if len(errors) > 50:
            print(f" - ... and {len(errors) - 50} more")
        print("Run python scripts/update-api-v2-symbol-baseline.py only for an intentional API contract change.")
        return 1

    file_counts = [int(item["file_count"]) for item in current_targets.values()]
    print(
        "API v2 symbol baseline OK: "
        f"{len(current_targets)} target(s), {sum(file_counts)} effective API file instance(s)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
