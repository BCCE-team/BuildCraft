#!/usr/bin/env python3
"""Refresh the checked-in API v2 source-symbol baseline after an intentional API change."""
from __future__ import annotations

import json
from pathlib import Path

from api_v2_surface import ROOT, snapshot

BASELINE = ROOT / "build-config" / "api-v2-symbol-baseline.json"


def main() -> int:
    value = snapshot()
    BASELINE.write_text(
        json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
        newline="",
    )
    total = sum(int(item["file_count"]) for item in value["targets"].values())
    print(f"Updated API v2 baseline for {len(value['targets'])} target(s), {total} effective API files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
