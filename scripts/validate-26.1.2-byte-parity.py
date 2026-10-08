#!/usr/bin/env python3
"""Verify the complete 26.1.2 effective tree against its reviewed SHA-256 manifest."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import tempfile

from source_layout import ROOT, load_properties, materialize_target

MANIFEST = ROOT / "build-config/materialized-baselines/26.1.2-neoforge.json"


def compare(root: Path, expected: dict[str, str]) -> list[str]:
    actual = {
        path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in root.rglob("*") if path.is_file()
    }
    return (
        [f"missing: {name}" for name in sorted(expected.keys() - actual.keys())]
        + [f"added: {name}" for name in sorted(actual.keys() - expected.keys())]
        + [f"changed: {name}" for name in sorted(expected.keys() & actual.keys())
           if expected[name] != actual[name]]
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, help="Compare an already materialized tree")
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if manifest["target"] != "26.1.2-neoforge" or manifest["algorithm"] != "sha256":
        raise ValueError("Unsupported materialized baseline")
    expected = manifest["files"]
    if args.source_root is not None:
        differences = compare(args.source_root, expected)
    else:
        with tempfile.TemporaryDirectory(prefix="bc-26.1.2-byte-parity-") as tmp:
            destination = Path(tmp) / "effective"
            materialize_target(manifest["target"], destination, load_properties())
            differences = compare(destination, expected)
    if differences:
        print("26.1.2 byte parity FAILED:")
        for difference in differences:
            print(f" - {difference}")
        return 1
    print(f"26.1.2 byte parity OK: {len(expected)} files; no added, missing or changed bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
