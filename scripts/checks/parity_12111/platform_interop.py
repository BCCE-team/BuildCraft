#!/usr/bin/env python3
"""Guard the 1.21.11 platform interop source paths and run deterministic Java unit probes.

This is not a Minecraft integration test. The Java probes compile maintained code
against offline API doubles; use Gradle and the runtime acceptance matrix as well.
"""
from __future__ import annotations

import argparse
import importlib.util
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def check(effective: Path) -> None:
    java = effective / "src/main/java"
    require(java.is_dir(), f"Missing effective Java tree: {java}")

    def src(relative: str) -> str:
        return (java / "buildcraft" / relative).read_text(encoding="utf-8")

    registration = src("lib/internal/capabilities/BCCapabilityRegistration.java")
    for kind, exporter in (("Item", "exportItems"), ("Fluid", "exportFluids"), ("Energy", "exportEnergy")):
        require(f"Capabilities.{kind}.BLOCK" in registration and exporter in registration,
                f"Missing standard {kind} registration / adapter")
    require("be.isRemoved() ? null" in registration and "be::setChanged" in registration,
            "Exports must re-resolve their sided owner and dirty it after commit")
    for module in ("core/BCCore", "energy/BCEnergy", "factory/BCFactory", "silicon/BCSilicon", "builders/BCBuilders", "transport/BCTransport"):
        require("BCCapabilityRegistration.registerBlockEntity" in src(module + ".java"),
                f"Module bypasses the shared registration path: {module}")
    for token in ("ENGINE_FE_TILE_BC8", "DYNAMO_MJ_TILE", "ENGINE_STONE_TILE_BC8", "ENGINE_IRON_TILE_BC8"):
        require(token in src("energy/BCEnergy.java"), f"Energy acceptance target omitted: {token}")
    require("exportItemSink" in src("transport/BCTransport.java"),
            "Item pipes need a native insertion port, not a fabricated inventory")

    interop = src("lib/compat/transfer/TransferInterop.java")
    for token in ("Transaction.open(transaction)", "Transaction.open(TransferJournal.current())", "IndexedFluidHandler", "importItems", "importFluids", "importEnergy"):
        require(token in interop, f"Transfer bridge lost {token}")
    require("Transaction.openRoot()" not in interop,
            "Legacy import must nest inside an existing transaction, never open a second root")
    journal = src("lib/compat/transfer/TransferJournal.java")
    for token in ("extends SnapshotJournal<S>", "updateSnapshots(transaction)", "revertToSnapshot", "onRootCommit", "getCurrentOpenedTransaction"):
        require(token in journal, f"Backing journal lost {token}")

    # A wrapper cannot supply rollback for an arbitrary simulate/execute backend.
    # Guard each BCCE mutation family that the public capability can reach.
    for relative in (
        "lib/tile/item/ItemHandlerSimple.java", "lib/fluid/Tank.java", "lib/internal/mj/MjBattery.java",
        "energy/tile/TileEngineFE.java", "energy/tile/TileDynamoMJ.java", "factory/tile/TileAutoWorkbenchBase.java",
        "silicon/tile/TileLaserTableBase.java", "transport/pipe/flow/PipeFlowItems.java",
        "transport/pipe/flow/PipeFlowFluids.java", "transport/pipe/flow/PipeFlowPower.java",
        "transport/pipe/flow/PipeFlowForgeEnergy.java", "robotics/entity/EntityRobot.java",
    ):
        text = src(relative)
        require("TransferJournal" in text and ".record()" in text,
                f"Exported mutation family is not journaled: {relative}")
    for relative in ("lib/fluid/TankManager.java", "energy/tile/TileEngineIron_BC8.java"):
        text = src(relative)
        require("IndexedFluidHandler" in text and "fillTank" in text and "drainTank" in text,
                f"Multi-tank handler lost positional transfer: {relative}")

    caps = src("lib/misc/CapUtil.java")
    for token in ("Capabilities.Item.ENTITY_AUTOMATION", "Capabilities.Item.ENTITY", "TransferInterop.importItems", "TransferInterop.importFluids", "TransferInterop.importEnergy"):
        require(token in caps, f"Modern import path lost {token}")
    helper = src("lib/inventory/ItemTransactorHelper.java")
    require("PlatformStorage.items(entity, face)" in helper,
            "Chute/Obsidian entity inventories no longer use the modern adapter")
    require("Object handler = entity.getCapability" not in helper,
            "Entity handler regressed to a ResourceHandler/IItemHandler runtime cast")
    for relative in ("lib/inventory/TransactorEntityItem.java", "lib/inventory/TransactorEntityArrow.java"):
        require("EntityTransferStorage" in src(relative), f"Entity extraction cannot roll back: {relative}")
    require("TransferJournal.active() ? null" in caps,
            "Foreign non-transactional fallback must not mutate during a native transaction")

    core = src("core/BCCore.java")
    require("Capabilities.Fluid.ITEM" in core and "new buildcraft.core.item.FragileFluidResourceHandler(context)" in core,
            "Shard native ItemAccess capability is not registered")
    shard = src("core/item/FragileFluidResourceHandler.java")
    for token in ("ItemAccess", "access.exchange(", "access.extract(", "Transaction.open(transaction)"):
        require(token in shard, f"Shard lost atomic item consumption/replacement: {token}")

    entity = src("builders/snapshot/SchematicEntityDefault.java")
    require("return BlueprintEntityData.save(entity);" in entity, "Entity capture is a placeholder")
    for token in ("EntityType.create(", "TagValueInput.create(", "EntitySpawnReason.LOAD", "entityNbt.copy()", 'putIntArray("block_pos"', 'putByte("facing"', 'putByte("Facing"', "BlueprintEntityData.restoreEquipment", "entity.snapTo(", "!serverLevel.addFreshEntity(entity)"):
        require(token in entity, f"Blueprint entity reconstruction lost {token}")
    data = src("builders/snapshot/BlueprintEntityData.java")
    for token in ("TagValueOutput.createWithContext", "entity.save(output)", "output.buildResult()", 'nbt.remove("equipment")', '"HandItems"', '"ArmorItems"', "stand.setItemSlot"):
        require(token in data, f"Entity serialization / material accounting lost {token}")
    block = src("builders/snapshot/SchematicBlockDefault.java")
    storage = src("lib/platform/storage/PlatformStorage.java")
    require(block.count("PlatformStorage.items(") >= 2 and "CapUtil.getItemHandler(level, pos, face)" in storage,
            "Both capture and deferred restoration must reach the standard sided capability import through PlatformStorage")
    require("PlatformStorage.localInventory(container)" in block and "items.wrapper.InvWrapper(inventory)" in storage,
            "Deferred restoration must address the local container, not a combined double-chest capability")
    architect = src("builders/tile/TileArchitectTable.java")
    require("player.isCreative()" in architect and "Permissions.COMMANDS_GAMEMASTER" in architect,
            "Creative blueprint permission no longer includes server operators")
    require("PlatformStorage.fluids(" in src("factory/tile/TileHeatExchange.java"),
            "Heat Exchanger output still only discovers private fluid capabilities")

    captured = src("lib/client/render/compat/CapturedBlockEntityRenderer.java")
    require("submitCustomGeometry" in captured and "List.copyOf" in captured,
            "Renderer must submit immutable extracted geometry to the native queue")
    for relative in ("factory/client/render/RenderDistiller.java", "factory/client/render/RenderHeatExchange.java", "factory/client/render/RenderPump.java", "factory/client/render/RenderMiningWell.java", "silicon/client/render/RenderProgrammingTable.java"):
        text = src(relative)
        require("BCGeometryRenderer<" in text and "renderContents(" in text,
                f"World renderer is still stranded on the laser-only legacy path: {relative}")
    geometry = src("lib/compat/minecraft/render/BCGeometryRenderer.java")
    require("extends CapturedBlockEntityRenderer<T>" in geometry and "renderContents(tile, partialTick, pose, buffers, light, overlay);" in geometry,
            "Geometry-only machines are not connected to the real immutable capture backend")
    require("TankRenderState" in src("factory/client/render/RenderTank.java"),
            "Do not replace the already-native Tank renderer")
    print("1.21.11 platform interop effective-source guards: OK")

    sys.path.insert(0, str(ROOT / "scripts/tests"))
    from transfer_fixture import run
    from transfer_probes import PROBES
    print(run(java, PROBES))
    print("1.21.11 platform interop validation: OK (offline unit probes; runtime integration still required)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--effective-root", type=Path, help="Use an already-materialized 1.21.11-neoforge tree")
    args = parser.parse_args()
    if args.effective_root:
        check(args.effective_root)
    else:
        spec = importlib.util.spec_from_file_location("platform_interop_source_layout", ROOT / "scripts/source_layout.py")
        layout = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = layout
        spec.loader.exec_module(layout)
        props = layout.load_properties(ROOT / "builds/modern/targets.properties")
        with tempfile.TemporaryDirectory(prefix="bc-12111-interop-") as tmp:
            root = Path(tmp) / "1.21.11-neoforge"
            layout.materialize_target("1.21.11-neoforge", root, props)
            check(root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
