#!/usr/bin/env python3
"""Keep audible feedback on all automated BuildCraft world mutations."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]

VARIANTS = (
    "source-platforms/forge/src/main/java/buildcraft/lib/misc/BlockUtil.java",
    "source-downports/1.21.X/1.21.1/neoforge/src/main/java/buildcraft/lib/misc/BlockUtil.java",
    "source-family-platforms/1.21.X/neoforge/src/main/java/buildcraft/lib/misc/BlockUtil.java",
    "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/lib/misc/BlockUtil.java",
)

STRIPES_VARIANTS = (
    "source-platforms/forge/src/main/java/buildcraft/transport/stripes/PipeExtensionManager.java",
    "source-platforms/neoforge/src/main/java/buildcraft/transport/stripes/PipeExtensionManager.java",
    "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/transport/stripes/PipeExtensionManager.java",
)

REQUIRED = (
    "boolean placed = PlatformWorldActions.placeBlock",
    "SoundUtil.playBlockPlace(level, pos, state);",
    "SoundUtil.playBlockBreak(world, pos, state);",
    "if (!world.destroyBlock(pos, true))",
)

errors = []
for rel in VARIANTS:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing {rel}")
        continue
    content = path.read_text(encoding="utf-8")
    for token in REQUIRED:
        if token not in content:
            errors.append(f"{rel}: missing automated sound feedback {token!r}")

for rel in STRIPES_VARIANTS:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing {rel}")
        continue
    if path.read_text(encoding="utf-8").count("SoundUtil.playBlockPlace(w, p, stripesStateOld);") != 2:
        errors.append(f"{rel}: both successful Stripes pipe moves must play a placement sound")

if errors:
    for error in errors:
        print("ERROR:", error)
    raise SystemExit(1)

print("Automated block place/break sound feedback guards OK")
