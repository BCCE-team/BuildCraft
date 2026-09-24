#!/usr/bin/env python3
"""Guard guidebook claims that are coupled to current gameplay/runtime contracts."""
from __future__ import annotations

import json
import re
from pathlib import Path

from source_lookup import resolve_target_source

ROOT = Path(__file__).resolve().parents[1]
GUIDE = ROOT / "source-shared/src/main/resources/assets/buildcraft/guide/text/en_us.json"


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def page(pages: dict[str, list[str]], key: str) -> str:
    value = pages.get(key)
    if not isinstance(value, list):
        fail(f"missing guide page {key}")
    return "\n".join(value)


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        fail(f"{label}: missing {needle!r}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        fail(f"{label}: stale claim still present: {needle!r}")



# Guide GUI translation keys must resolve in the built-in English locale. This catches
# key drift between GuiGuide and en_us.json (for example loaded vs loaded_modules).
LANG = ROOT / "source-shared/src/main/resources/assets/buildcraft/lang/en_us.json"
lang = json.loads(LANG.read_text(encoding="utf-8"))
guide_gui_paths = tuple(
    resolve_target_source(target, "src/main/java/buildcraft/lib/client/guide/GuiGuide.java")
    for target in ("1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge", "1.21.11-neoforge")
)
key_pattern = re.compile(r'"(buildcraft\.guide\.contents\.[a-zA-Z0-9_.-]+)"')
for gui_path in guide_gui_paths:
    gui_source = gui_path.read_text(encoding="utf-8")
    for translation_key in sorted(set(key_pattern.findall(gui_source))):
        if translation_key not in lang:
            fail(f"{gui_path.relative_to(ROOT)} references missing translation key {translation_key!r}")

root = json.loads(GUIDE.read_text(encoding="utf-8"))
pages = root.get("pages")
if not isinstance(pages, dict):
    fail("English guide pack has no pages object")

checks = {
    "tank": page(pages, "buildcraftfactory/block/tank"),
    "iron item": page(pages, "buildcrafttransport/pipe/iron_item"),
    "iron fluid": page(pages, "buildcrafttransport/pipe/iron_fluid"),
    "diamond fluid": page(pages, "buildcrafttransport/pipe/diamond_fluid"),
    "obsidian item": page(pages, "buildcrafttransport/pipe/obsidian_item"),
    "chute": page(pages, "buildcraftfactory/block/chute"),
    "quarry": page(pages, "buildcraftbuilders/block/quarry"),
    "redstone engine": page(pages, "buildcraftcore/block/engine_wood"),
    "stirling engine": page(pages, "buildcraftenergy/block/engine_stone"),
    "facades": page(pages, "buildcraftsilicon/item/plug_facade"),
    "pump": page(pages, "buildcraftfactory/block/pump"),
    "iron power": page(pages, "buildcrafttransport/pipe/iron_power"),
    "stone fluid": page(pages, "buildcrafttransport/pipe/stone_fluid"),
    "water gel": page(pages, "buildcraftfactory/item/water_gel"),
}

require(checks["tank"], "Fragile Fluid Shards", "tank")
forbid(checks["tank"], "wont get the fluids back", "tank")
for label in ("iron item", "iron fluid"):
    require(checks[label], "does <bold>not</bold> rotate", label)
    forbid(checks[label], "giving it a redstone signal", label)
require(checks["diamond fluid"], "does <bold>not</bold> add routing weight", "diamond fluid")
forbid(checks["diamond fluid"], "add 'weight'", "diamond fluid")
require(checks["obsidian item"], "up to four blocks", "obsidian item")
forbid(checks["obsidian item"], "3x4x3", "obsidian item")
require(checks["chute"], "facing side", "chute")
require(checks["chute"], "other five sides", "chute")
require(checks["quarry"], "does not halt the Quarry", "quarry")
require(checks["quarry"], "future frame line", "quarry")
forbid(checks["quarry"], "blocked output can stall", "quarry")
require(checks["redstone engine"], "Overheat", "redstone engine")
require(checks["redstone engine"], "Creative Engine", "redstone engine")
require(checks["stirling engine"], "engines.stirlingExplosion", "stirling engine")
require(checks["stirling engine"], "defaults to <code>false</code>", "stirling engine")
require(checks["facades"], "facades.enable", "facades")
require(checks["facades"], "already installed facades continue to load and render", "facades")
require(checks["pump"], "in any direction", "pump")
require(checks["pump"], "Unloaded chunks are not pulled in", "pump")
forbid(checks["pump"], "horizontal reach", "pump")
require(checks["iron power"], "not a directional-output pipe", "iron power")
require(checks["stone fluid"], "Stone Fluid Pipe transports", "stone fluid")
forbid(checks["stone fluid"], "Cobblestone Fluid pipe transports", "stone fluid")
require(checks["water gel"], "randomTickSpeed", "water gel")
require(checks["water gel"], "Only fully solidified Water Gel drops", "water gel")
forbid(checks["water gel"], "slows the final solidification", "water gel")

for key in (
    "buildcraftcore/item/map_location",
    "buildcraftbuilders/item/schematic_single",
    "buildcraftbuilders/item/blueprint",
    "buildcraftbuilders/item/template",
):
    text = page(pages, key)
    require(text, "stack to <bold>16</bold>", key)
    require(text, "maximum stack size of <bold>1</bold>", key)

print("Guide/runtime claims OK: transport, factory, builders, engines, facades and 16/1 utility-item stacks guarded")
