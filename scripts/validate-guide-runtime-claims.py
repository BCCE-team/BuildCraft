#!/usr/bin/env python3
"""Guard guidebook claims that are coupled to current gameplay/runtime contracts."""
from __future__ import annotations

import json
import re
from pathlib import Path

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
guide_gui_paths = (
    ROOT / "version-src/1.19.2-forge/src/main/java/buildcraft/lib/client/guide/GuiGuide.java",
    ROOT / "version-src/1.20.1-forge/src/main/java/buildcraft/lib/client/guide/GuiGuide.java",
    ROOT / "source-families/modern/src/main/java/buildcraft/lib/client/guide/GuiGuide.java",
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

# Robotics programming costs follow the restored BC7 balance converted at 10 legacy energy units = 1 MJ.
redstone_board = page(pages, "buildcraftrobotics/item/redstone_board")
programming_table = page(pages, "buildcraftsilicon/block/programming_table")
integration_table = page(pages, "buildcraftsilicon/block/integration_table")
for text, label in ((redstone_board, "redstone board"), (programming_table, "programming table")):
    for cost in ("800 MJ", "3,200 MJ", "12,800 MJ", "51,200 MJ"):
        require(text, cost, label)
    for stale in ("8,000 MJ", "32,000 MJ", "64,000 MJ", "128,000 MJ"):
        forbid(text, stale, label)
require(redstone_board, "Installing a board requires 5,000 MJ", "redstone board")
require(integration_table, "That operation requires 5,000 MJ", "integration table")
forbid(redstone_board, "Installing a board requires 10,000 MJ", "redstone board")
forbid(integration_table, "That operation requires 10,000 MJ", "integration table")

robot_career_costs = {
    "picker": "800 MJ",
    "carrier": "800 MJ",
    "fluid_carrier": "800 MJ",
    "lumberjack": "3,200 MJ",
    "harvester": "3,200 MJ",
    "miner": "3,200 MJ",
    "planter": "3,200 MJ",
    "farmer": "3,200 MJ",
    "leave_cutter": "3,200 MJ",
    "butcher": "3,200 MJ",
    "shovelman": "3,200 MJ",
    "pump": "3,200 MJ",
    "delivery": "12,800 MJ",
    "knight": "12,800 MJ",
    "bomber": "12,800 MJ",
    "stripes": "12,800 MJ",
    "builder": "51,200 MJ",
}
for career, cost in robot_career_costs.items():
    text = page(pages, f"buildcraftrobotics/robot/{career}")
    require(text, f"programmed into a Redstone Board for {cost}", f"robot {career}")

# Station actions are filters over robot parameters; Go to Station additionally accepts a Map Location target.
forbid_robot = page(pages, "buildcraftrobotics/action/forbid_robot")
force_robot = page(pages, "buildcraftrobotics/action/force_robot")
goto_station = page(pages, "buildcraftrobotics/action/goto_station")
require(forbid_robot, "robots matching the configured robot parameters", "forbid robot action")
require(force_robot, "only robots matching the configured robot parameters", "force robot action")
forbid(force_robot, "prefer or return to this station", "force robot action")
require(goto_station, "Docking Station stored in a Map Location parameter", "go to station action")

# Recent table behaviour: direct MJ charging and deterministic selection when multiple recipes match one phantom grid.
charging_table = page(pages, "buildcraftsilicon/block/charging_table")
require(charging_table, "Supply MJ directly or with nearby Lasers", "charging table")
forbid(charging_table, "uses laser power to charge Forge Energy items", "charging table")
auto_workbench = page(pages, "buildcraftfactory/block/auto_workbench")
advanced_crafting = page(pages, "buildcraftsilicon/block/advanced_crafting_table")
require(auto_workbench, "Recipe Book can fill the phantom grid directly without consuming ingredients", "auto workbench")
require(auto_workbench, "left-click the recipe output selector for the next recipe", "auto workbench")
require(auto_workbench, "right-click for the previous one; the choice is saved", "auto workbench")
require(advanced_crafting, "Recipe Book can fill the phantom grid directly without consuming ingredients", "advanced crafting table")
require(advanced_crafting, "left-click the result preview for the next recipe", "advanced crafting table")
require(advanced_crafting, "right-click for the previous one; the choice is saved", "advanced crafting table")

# The local blueprint library and the world's machine storage are deliberately separate, even in singleplayer.
library = page(pages, "buildcraftbuilders/block/library")
blueprint = page(pages, "buildcraftbuilders/item/blueprint")
require(library, "player's local blueprint library", "electronic library")
require(library, "current world's machine storage", "electronic library")
require(library, "stores remain separate even in singleplayer", "electronic library")
require(library, "reuses the server-side copy when it already exists", "electronic library")
forbid(library, "Use one Library as the project archive and another near the work site", "electronic library")
require(blueprint, "save a master copy to your local blueprint library", "blueprint")

filler_planner = page(pages, "buildcraftbuilders/item/filler_planner")
require(filler_planner, "Open the Filler interface to configure the planner's pattern", "filler planner")
require(filler_planner, "settings are stored in the Volume Box add-on", "filler planner")
forbid(filler_planner, "resize and inspect the planned operation", "filler planner")

print("Guide/runtime claims OK: transport, factory, builders, robotics, silicon, engines, facades and utility-item stacks guarded")
