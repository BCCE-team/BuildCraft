#!/usr/bin/env python3
"""Offline structural validation for the maintained BuildCraft Forge 1.20.1 target."""
from __future__ import annotations

import argparse
import json
import re
import sys
import tomllib
from pathlib import Path

from source_layout import configured_layer_paths, load_properties as load_layout_properties, materialize_target


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def scan_java(path: Path) -> None:
    text = path.read_text(encoding="utf-8", errors="strict")
    state = "normal"
    escaped = False
    line = 1
    stack: list[tuple[str, int]] = []
    pairs = {')': '(', ']': '[', '}': '{'}
    i = 0
    while i < len(text):
        char = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        tri = text[i:i + 3]
        if char == "\n":
            line += 1
            if state == "line_comment":
                state = "normal"
            i += 1
            continue
        if state == "line_comment":
            i += 1
            continue
        if state == "block_comment":
            if char == "*" and nxt == "/":
                state = "normal"
                i += 2
            else:
                i += 1
            continue
        if state == "text_block":
            if tri == '"""':
                state = "normal"
                i += 3
            else:
                i += 1
            continue
        if state in {"string", "char"}:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif (state == "string" and char == '"') or (state == "char" and char == "'"):
                state = "normal"
            i += 1
            continue

        if char == "/" and nxt == "/":
            state = "line_comment"
            i += 2
        elif char == "/" and nxt == "*":
            state = "block_comment"
            i += 2
        elif tri == '"""':
            state = "text_block"
            i += 3
        elif char == '"':
            state = "string"
            i += 1
        elif char == "'":
            state = "char"
            i += 1
        elif char in "([{":
            stack.append((char, line))
            i += 1
        elif char in ")]}" :
            if not stack or stack[-1][0] != pairs[char]:
                fail(f"{path}:{line}: unmatched {char}")
            stack.pop()
            i += 1
        else:
            i += 1
    if state in {"block_comment", "string", "char", "text_block"}:
        fail(f"{path}: unclosed Java lexical state: {state}")
    if stack:
        char, opened = stack[-1]
        fail(f"{path}:{opened}: unclosed {char}")


def expanded_text(path: Path, numeric: bool = False) -> str:
    replacement = "1" if numeric else "x"
    return re.sub(r"\$\{[A-Za-z0-9_.-]+}", replacement, path.read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, help="standalone source tree; configured shared/family/platform/target layers are merged automatically")
    parser.add_argument("--target", default="1.20.1-forge")
    parser.add_argument("--expected-minecraft", default="1.20.1")
    parser.add_argument("--expected-protocol", default="BC8.0.x-1.20.1-net2")
    args = parser.parse_args()

    properties = load_layout_properties()
    configured_layers = set(configured_layer_paths(args.target, properties))
    requested = args.source_root.resolve() if args.source_root else None
    root = materialize_target(args.target, properties=properties) if requested is None or requested in configured_layers else requested
    src = root / "src"
    if not (src / "main/java").is_dir():
        fail(f"missing source tree: {src / 'main/java'}")

    required = [
        "src/main/java/buildcraft/lib/client/guide/GuiGuide.java",
        "src/main/java/buildcraft/lib/item/ItemGuide.java",
        "src/main/java/buildcraft/compat/forestry/pipe/PipeBehaviourPropolis.java",
        "src/main/java/buildcraft/compat/jei/BuildCraftJeiPlugin.java",
        "src/main/java/buildcraft/lib/misc/FluidUtilBC.java",
        "src/main/java/buildcraft/robotics/zone/ZonePlannerMapChunk.java",
        "src/main/resources/assets/buildcraft/guide/original_manifest.json",
    ]
    for rel in required:
        if not (root / rel).is_file():
            fail(f"missing required 1.20.1 port file: {rel}")

    text_files = [p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in {
        ".java", ".json", ".mcmeta", ".toml", ".properties", ".gradle", ".kts", ".yml", ".yaml", ".md", ".sh", ".py"
    }]
    for path in text_files:
        text = path.read_text(encoding="utf-8", errors="strict")
        if re.search(r"^(?:<<<<<<<|>>>>>>>)(?: .*)?$", text, flags=re.MULTILINE):
            fail(f"merge marker remains in {path}")
        if path.suffix.lower() != ".md" and path.resolve() != Path(__file__).resolve() and "8.0.11" in text:
            fail(f"old 8.0.11 version remains in {path}")

    java_files = list((src / "main/java").rglob("*.java"))
    for path in java_files:
        scan_java(path)

    for path in src.rglob("*.json"):
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            fail(f"invalid JSON {path}: {exc}")
    for path in src.rglob("*.mcmeta"):
        try:
            json.loads(expanded_text(path, numeric=True))
        except Exception as exc:
            fail(f"invalid mcmeta template {path}: {exc}")
    for path in src.rglob("*.toml"):
        try:
            tomllib.loads(expanded_text(path))
        except Exception as exc:
            fail(f"invalid TOML template {path}: {exc}")

    checks = {
        "src/main/java/buildcraft/lib/net/MessageManager.java": [
            "BuildCraftTarget.NETWORK_PROTOCOL",
        ],
        "src/main/java/buildcraft/lib/net/MessageUpdateTile.java": [
            "hasChunkAt(message.pos)",
            "canInteractWith(sender)",
            "MAX_PAYLOAD_SIZE",
        ],
        "src/main/java/buildcraft/robotics/zone/ZonePlannerMapChunk.java": [
            "getChunkNow",
        ],
        "src/main/java/buildcraft/robotics/tile/TileZonePlanner.java": [
            "getChunkNow",
        ],
        "src/main/java/buildcraft/lib/misc/FluidUtilBC.java": [
            "FluidAction.SIMULATE",
            "FluidAction.EXECUTE",
            "restoreFluid",
        ],
        "src/main/java/buildcraft/transport/pipe/flow/PipeFlowPower.java": [
            "saturatingAdd",
            "receivePowerInternal",
        ],
        "src/main/java/buildcraft/transport/item/ItemPipeHolder.java": [
            "PIPE_COLOR_TAG",
            "import net.minecraft.world.item.DyeColor;",
        ],
        "src/main/java/buildcraft/silicon/client/render/RenderProgrammingTable.java": [
            "whiteStainedGlass.getU",
        ],
    }
    for rel, needles in checks.items():
        text = (root / rel).read_text(encoding="utf-8")
        for needle in needles:
            if needle not in text:
                fail(f"missing expected 1.20.1 port invariant {needle!r} in {rel}")

    print(
        f"BuildCraft Forge 1.20.1 port layout OK: {len(java_files)} Java files, "
        f"Minecraft={args.expected_minecraft}, protocol={args.expected_protocol}"
    )


if __name__ == "__main__":
    main()
