#!/usr/bin/env python3
"""Guards the 1.21.11 world/state gameplay parity invariants."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]


def load_layout():
    spec = importlib.util.spec_from_file_location("world_state_source_layout", ROOT / "scripts/source_layout.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    layout = load_layout()
    props = layout.load_properties(ROOT / "builds/modern/targets.properties")
    with tempfile.TemporaryDirectory(prefix="bc-12111-world-state-") as tmp:
        root = Path(tmp) / "1.21.11"
        layout.materialize_target("1.21.11-neoforge", root, props)
        java = root / "src/main/java/buildcraft"

        def src(rel: str) -> str:
            return (java / rel).read_text(encoding="utf-8")

        wires = src("transport/wire/WorldSavedDataWireSystems.java")
        require("SavedDataType<WorldSavedDataWireSystems> TYPE" in wires,
                "Wire systems are not backed by 1.21.11 SavedDataType")
        require("getDataStorage().computeIfAbsent(TYPE)" in wires,
                "Wire system get() still returns a transient instance")
        require("new WorldSavedDataWireSystems();\n        instance.world" not in wires,
                "Wire system persistence regressed to a fresh object per get()")

        pipe = src("transport/block/BlockPipeHolder.java")
        require("ItemStack toolStack, boolean willHarvest, FluidState fluid" in pipe,
                "Pipe selected-part destruction still uses the obsolete 1.21.1 callback")
        require("super.onDestroyedByPlayer(state, world, pos, player, toolStack, willHarvest, fluid)" in pipe,
                "Pipe fallback no longer delegates to the real 1.21.11 block destruction path")
        require("InsideBlockEffectApplier effectApplier, boolean submerged" in pipe,
                "Obsidian pipe collision callback is not a live 1.21.11 entityInside override")
        require("pipe.getBehaviour().onEntityCollide(entity);" in pipe,
                "Obsidian/item collision dispatch was lost")

        stripes = src("transport/stripes/PipeExtensionManager.java")
        require("TagValueInput.create" in stripes and "loadWithComponents" in stripes,
                "Stripes pipe move does not restore full block-entity/components NBT")

        liquid = src("energy/fluid/BCLiquidBlock.java")
        require("InsideBlockEffectApplier effectApplier, boolean submerged" in liquid,
                "BCLiquidBlock entityInside is still on the dead 1.21.1 signature")
        require("entity.makeStuckInBlock" in liquid and "entity.lavaHurt()" in liquid,
                "Sticky/searing fluid gameplay effects were not preserved")

        bcfluid = src("lib/fluid/BCFluid.java")
        for token in ("canPassThroughWall0(", "fluidState.canBeReplacedWith(", "canHoldFluid("):
            require(token in bcfluid, f"BC gaseous spread lost vanilla restriction: {token}")
        require("fluidState.getFluidType().getDensity() < this.getFluidType().getDensity()" in bcfluid,
                "BC density ordering was lost while restoring vanilla spread restrictions")

        block_tile = src("lib/block/BlockBCTile_Neptune.java")
        require("Orientation orientation, boolean harvest" in block_tile,
                "BuildCraft tile blocks do not override the actual 1.21.11 neighbor callback")
        require("tileBC.neighbourBlockChanged(state, fromPos, harvest);" in block_tile,
                "1.21.11 neighbor callback does not reach tile logic")

        builders_nbt = src("builders/BuildersNbtUtil.java")
        require("tryReadBlockPos(parent.get(key)).orElse(BlockPos.ZERO)" in builders_nbt,
                "Old blueprint BlockPos layouts are no longer decoded")
        for form in ('"X", "Y", "Z"', '"x", "y", "z"', '"i", "j", "k"'):
            require(form in builders_nbt, f"Blueprint BlockPos decoder lost legacy coordinate set {form}")
        require("IntArrayTag" in builders_nbt and 'compound.contains("pos")' in builders_nbt,
                "Blueprint decoder lost int-array/nested-pos formats")

        mining = src("factory/tile/TileMiningWell.java")
        require("super.onRemove(dropSelf);" in mining,
                "Mining Well no longer reaches TileMiner shaft cleanup")

        heat = src("factory/block/BlockHeatExchange.java")
        require("Orientation orientation, boolean notify" in heat,
                "Heat Exchanger still uses the obsolete neighborChanged callback")
        require("super.neighborChanged(state, level, pos, block, orientation, notify);" in heat,
                "Heat Exchanger neighbor changes do not reach TileHeatExchange rebuild logic")

        tank = src("factory/block/BlockTank.java")
        require("Orientation orientation, boolean notify" in tank,
                "Tank joined-state still uses the obsolete neighbor callback")
        require("affectNeighborsAfterRemoval" in tank and "updateJoinedBelow(level, pos.above()" in tank,
                "Tank above a removed tank is not forced to recompute joined state")

        print("1.21.11 world/state gameplay parity validation: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
