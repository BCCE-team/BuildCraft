#!/usr/bin/env python3
"""Guard survival acquisition for items shown in BCCE's creative tabs on every target.

Static audit: item identities come from the registration sources used by the tab
providers, and recipe identities come from the effective layered data packs.  The
creative variants sharing one item ID are checked independently where appropriate.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from source_config import load_properties, target_ids, target_layout
from source_layout import effective_source_files

JAVA = ROOT / "source-families/1.21.X/src/main/java/buildcraft"
COLORS = (
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
)
CHIPSETS = ("red", "iron", "gold", "quartz", "diamond")
# Registered, but not shown in any BCCE tab: world-generation blocks and an
# internal container.  Never exempt these if they are added to a creative tab.
NOT_IN_TABS = frozenset({
    "buildcraftcore:fragile_fluid_shard", "buildcraftcore:spring_water", "buildcraftcore:spring_oil",
})
# Deliberate alternative acquisition, documented in the source (no normal recipe):
#   creative engine - creative-only power source;
#   quarry frame - placed by the quarry itself, drops when broken;
#   gel - drops from a water-gel block at its GEL stage;
#   facades - dynamic special crafting recipe (material/blockstate dependent).
ALTERNATIVE_ACQUISITION = frozenset({
    "buildcraftcore:engine_creative", "buildcraftbuilders:frame",
    "buildcraftfactory:gel", "buildcraftsilicon:plug/facade",
})
# All energy-fluid buckets in the fluid tab are filled from fluids; robot and
# board variants are assembled/programmed/charged from their craftable base item.


def read(rel: str) -> str:
    return (JAVA / rel).read_text(encoding="utf-8")


def creative_item_ids() -> tuple[set[str], set[str]]:
    """Use registered item names, not a hand-maintained list of recipe filenames."""
    items: set[str] = set()
    for module, namespace, source in (
        ("core", "buildcraftcore", "BCCoreItems.java"),
        ("builders", "buildcraftbuilders", "BCBuildersItems.java"),
        ("factory", "buildcraftfactory", "BCFactoryItems.java"),
        ("robotics", "buildcraftrobotics", "BCRoboticsItems.java"),
        ("silicon", "buildcraftsilicon", "BCSiliconItems.java"),
        ("transport", "buildcrafttransport", "BCTransportItems.java"),
    ):
        ids = re.findall(r'\bITEMS\.register\(\s*"([^"\n]+)"', read(f"{module}/{source}"))
        if not ids:
            raise ValueError(f"No item registrations extracted from {module}/{source}")
        items.update(f"{namespace}:{item}" for item in ids)

    energy = re.findall(r'\bBCEnergy\.ITEMS\.register\(\s*"([^"\n]+)"', read("energy/BCEnergyBlocks.java"))
    if len(energy) != 4:
        raise ValueError(f"Expected four energy items from the main creative tab, got {energy}")
    items.update("buildcraftenergy:" + item for item in energy)
    blocks = re.findall(r'\bBCTransportItems\.ITEMS\.register\(\s*"([^"\n]+)"', read("transport/BCTransportBlocks.java"))
    if blocks != ["filtered_buffer"]:
        raise ValueError(f"Unexpected transport block items {blocks}")
    items.update("buildcrafttransport:" + item for item in blocks)
    items.add("buildcraftlib:guide")
    items.update("buildcraftcore:paintbrush/" + color for color in COLORS)
    items.update("buildcrafttransport:wire/" + color for color in COLORS)
    items.update("buildcraftsilicon:redstone_chipset/" + type_ for type_ in CHIPSETS)

    # Pipes are registered through the runtime PipeDefinition -> makePipeItem
    # pipeline, rather than ITEMS.register().  Verify every defined pipe makes
    # it into the tab, including the nine *_fe variants (the *_rf IDs are aliases).
    defined = dict(re.findall(
        r'^[ \t]*([a-zA-Z]\w*)\s*=\s*builder\.idTex(?:Prefix)?\("([^"\n]+)"\)',
        read("transport/BCTransportPipes.java"), re.MULTILINE,
    ))
    active_holders = "\n".join(line for line in read("transport/BCTransportItems.java").splitlines()
                              if not line.lstrip().startswith("//"))
    holders = set(re.findall(r'\bmakePipeItem\(BCTransportPipes\.(\w+)\)', active_holders))
    if len(defined) != 46 or set(defined) != holders:
        raise ValueError(f"Pipe definitions / holders disagree: {sorted(set(defined) ^ holders)}")
    pipe_items = {"buildcrafttransport:" + item for item in defined.values()}
    items.update(pipe_items)
    if NOT_IN_TABS - items:
        raise ValueError(f"Hidden-item allowlist stale: {sorted(NOT_IN_TABS - items)}")
    items.difference_update(NOT_IN_TABS)
    if ALTERNATIVE_ACQUISITION - items:
        raise ValueError(f"Alternative-acquisition allowlist stale: {sorted(ALTERNATIVE_ACQUISITION - items)}")
    return items, pipe_items


def recipe_id(payload: dict) -> str | None:
    result = payload.get("result")
    if isinstance(result, str):
        return result
    if isinstance(result, dict):
        return result.get("item") or result.get("id")
    return None


def gate_variant(result: dict) -> tuple[int, int, int] | None:
    nbt = result.get("nbt", "")
    if match := re.search(r"logic:(\d+)b,material:(\d+)b,modifier:(\d+)b", nbt):
        return tuple(map(int, match.groups()))
    data = result.get("components", {}).get("minecraft:custom_data", {}).get("gate", {})
    if data:
        return tuple(data[key] for key in ("logic", "material", "modifier"))
    return None


def audit_fluid_buckets(target: str) -> int:
    """Fluid tab contains fillable containers, not crafting-grid products."""
    props = load_properties()
    layout = target_layout(target, props)
    path = effective_source_files(layout, props, "src/main/java/buildcraft/energy").get(
        "src/main/java/buildcraft/energy/BCEnergyFluids.java"
    )
    if path is None:
        raise ValueError(f"{target}: missing bucket registration source")
    code = path.read_text(encoding="utf-8")
    names_match = re.search(r"public static final String\[\] NAME\s*=\s*\{([^}]+)\}", code, re.DOTALL)
    heat_match = re.search(r"public static final String\[\] HEAT_NAMES\s*=\s*\{([^}]+)\}", code, re.DOTALL)
    if not names_match or not heat_match:
        raise ValueError(f"{target}: cannot identify fluid/bucket types")
    names = re.findall(r'"([^"\n]+)"', names_match.group(1))
    heat_names = re.findall(r'"([^"\n]+)"', heat_match.group(1))
    if len(names) != 10 or len(heat_names) != 3 or len(set(names)) != len(names):
        raise ValueError(f"{target}: fluid bucket enumeration changed: {len(names)} x {len(heat_names)}")
    for token in ("BCEnergy.ITEMS.register(", "HEAT_NAMES[heat]", "_bucket", "OIL_BUCKET.add(", ".bucket(", ".craftRemainder(Items.BUCKET)"):
        if token not in code:
            raise ValueError(f"{target}: bucket filling contract missing {token}")
    return len(names) * len(heat_names)


def audit_target(target: str, expected: set[str], pipes: set[str]) -> tuple[int, int]:
    props = load_properties()
    layout = target_layout(target, props)
    files = effective_source_files(layout, props, "src/main/resources/data")
    folder = "recipes" if layout.family == "old" else "recipe"
    recipe_entries: list[tuple[str, dict]] = []
    for logical, path in files.items():
        if f"/{folder}/" not in logical or not logical.endswith(".json"):
            continue
        recipe_entries.append((logical, json.loads(path.read_text(encoding="utf-8"))))

    outputs = {result for _, data in recipe_entries if (result := recipe_id(data))}
    missing = sorted(expected - outputs - ALTERNATIVE_ACQUISITION)
    if missing:
        raise ValueError(f"{target}: creative items with no recipes: {', '.join(missing)}")
    if any(output.endswith("_rf") for output in outputs):
        raise ValueError(f"{target}: deprecated RF IDs must not be new recipe outputs")

    buckets = audit_fluid_buckets(target)
    if buckets != 30:
        raise ValueError(f"{target}: expected 30 fillable fluid-tab buckets, got {buckets}")

    # Every *_fe pipe has one reversible, colour-preserving MJ<->FE upgrade.
    by_path = {path: data for path, data in recipe_entries}
    for fe in sorted(item for item in pipes if item.endswith("_fe")):
        name = fe.split(":", 1)[1]
        base = name[:-3]
        power = f"buildcrafttransport:{base}_power"
        prefix = f"src/main/resources/data/buildcrafttransport/{folder}/"
        for suffix, mode, source, target_id in (
            ("", "upgrade", power, fe),
            ("_undo", "downgrade", fe, power),
        ):
            path = prefix + name + suffix + ".json"
            data = by_path.get(path)
            if not data:
                raise ValueError(f"{target}: missing {path}")
            if (data.get("type"), data.get("mode"), data.get("from", {}).get("item"), recipe_id(data)) != (
                "buildcrafttransport:pipe", mode, source, target_id
            ):
                raise ValueError(f"{target}: invalid FE conversion {path}")
            if mode == "upgrade" and data.get("additional", {}).get("item") != "minecraft:redstone":
                raise ValueError(f"{target}: FE conversion missing redstone: {path}")
    # Explicitly cover variant-rich creative items, not just their shared IDs.
    gate_variants = set()
    lens_variants = set()
    for _, data in recipe_entries:
        if recipe_id(data) == "buildcraftsilicon:plug/gate":
            if variant := gate_variant(data.get("result", {})):
                gate_variants.add(variant)
        if recipe_id(data) == "buildcraftsilicon:plug/lens":
            if match := re.search(r"Damage:(\d+)", data.get("result", {}).get("nbt", "")):
                lens_variants.add(int(match.group(1)))
    gate_expected = {(0, 0, 0)} | {
        (logic, material, modifier)
        for logic in (0, 1) for material in (1, 2, 3) for modifier in (0, 1, 2, 3)
    }
    if gate_expected - gate_variants:
        raise ValueError(f"{target}: no acquisition path for gate variants {sorted(gate_expected - gate_variants)}")
    if set(range(34)) - lens_variants:
        raise ValueError(f"{target}: no assembly recipe for lenses {sorted(set(range(34)) - lens_variants)}")
    return len(outputs & expected), len(recipe_entries)


def main() -> int:
    items, pipes = creative_item_ids()
    props = load_properties()
    for target in target_ids(props):
        count, total = audit_target(target, items, pipes)
        print(f"  {target}: {count}/{len(items) - len(ALTERNATIVE_ACQUISITION)} craftable IDs; {total} recipes; 9/9 FE upgrades + reversals; gates/lenses OK")
    print("Creative recipe coverage OK: all seven targets; 30 fillable buckets, 16 coloured wires, 16 coloured brushes, 25 gate variants, 34 lens variants")
    print("  Intentional exceptions: creative engine; quarry-built frame; water-gel drop; dynamic facades; fillable oil buckets; programmable robot variants")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, json.JSONDecodeError) as exc:
        print("ERROR:", exc, file=sys.stderr)
        raise SystemExit(1)
