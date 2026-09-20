#!/usr/bin/env python3
"""Guards for deferred blueprint inventory restoration.

These are source/materialization contracts, not a Minecraft runtime acceptance test. They
protect inventory-copy invariants whose violation can create dupes or losses:
sided capability-only inventories, partial contents, component-sensitive stacks, opaque
serialization, failed restore, and missing registry items.
"""
from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_layout import load_properties, materialize_target
from minecraft_compat_fixture import parse_sources

TARGETS = ("1.21.1-neoforge", "1.21.11-neoforge")
SCHEMATIC = "buildcraft/builders/snapshot/SchematicBlockDefault.java"
SINGLE = "buildcraft/builders/item/ItemSchematicSingle.java"


class InventoryCopyEdgeCases(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temp = tempfile.TemporaryDirectory(prefix="bc-inventory-copy-")
        cls.props = load_properties()
        cls.roots: dict[str, Path] = {}
        for target in TARGETS:
            out = Path(cls.temp.name) / target
            materialize_target(target, out, cls.props)
            cls.roots[target] = out

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temp.cleanup()

    def java(self, target: str, logical: str) -> str:
        return (self.roots[target] / "src/main/java" / logical).read_text(encoding="utf-8")

    def schematic(self, target: str) -> str:
        return self.java(target, SCHEMATIC)

    def test_materialized_modern_sources_parse(self) -> None:
        print(parse_sources([root / "src/main/java" for root in self.roots.values()]))

    def test_sided_and_local_inventory_capture(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                source = self.schematic(target)
                for marker in (
                    "blockEntity instanceof Container container",
                    "PlatformStorage.localInventory(container)",
                    "findDeferredInventory(level, blockEntity.getBlockPos())",
                    "for (Direction side : Direction.values())",
                    "new DeferredInventoryItem(slot, accessSide, stack)",
                    'nbt.putString("side", accessSide.getName())',
                    "getDeferredInventoryHandler(level, blockPos, blockEntity, entry.accessSide)",
                ):
                    self.assertIn(marker, source)
                # Capability-only sided views are selected conservatively instead of concatenated,
                # which would count the same physical inventory once per exposed face.
                self.assertIn("richest stable view", source)

    def test_partial_contents_are_slot_exact(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                source = self.schematic(target)
                for marker in (
                    "getMissingDeferredCount(",
                    "entry.stack.getCount() - present.getCount()",
                    "int attemptCount = Math.min(missing, remaining.getCount())",
                    "insertIntoExactSlot(handler, entry.slot, attempt, simulate)",
                ):
                    self.assertIn(marker, source)
                self.assertNotIn(
                    "for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++)",
                    source,
                )
                self.assertNotIn("preferredSlots", source)

    def test_components_survive_capture_compare_and_serialization(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                source = self.schematic(target)
                self.assertGreaterEqual(source.count("ItemStack.isSameItemSameComponents"), 4)
                self.assertIn("ItemStackUtil.saveOptional(stack", source)
                self.assertIn("ItemStackUtil.parseOptional(", source)
                # Recursive deletion of arbitrary ItemStack-looking NBT can erase data components
                # or machine configuration that is not inventory content.
                self.assertNotIn("removeSerializedDeferredItems", source)
                self.assertNotIn("isDeferredItemStack", source)

    def test_opaque_nonstandard_handlers_fail_closed_without_drops(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                source = self.schematic(target)
                for marker in (
                    "deferInventoryRuntimeClear",
                    "clearRuntimeDeferredInventory(level, blockPos, tileEntity)",
                    "rollbackOpaqueInventoryPlacement(level, blockPos)",
                    "level.removeBlockEntity(blockPos)",
                    "level.setBlock(blockPos, Blocks.AIR.defaultBlockState(), 3)",
                    "catch (RuntimeException ignored)",
                    "observedInsertRemainder(",
                    "sameStackAndCount(current, restored)",
                ):
                    self.assertIn(marker, source)
                # Unknown serialization keeps non-inventory BE state, but it must never be
                # accepted as a successful build unless copied contents can be cleared at runtime.
                self.assertIn("tileNbt = originalTileNbt.copy()", source)
                self.assertIn("deferInventoryRuntimeClear = true", source)

    def test_failed_restore_refunds_or_drops_exact_remainder(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                single = self.java(target, SINGLE)
                for marker in (
                    "ItemStack overflow = schematicBlock.insertDeferredItem(world, blockPos, supplied, false)",
                    "ItemStack refundRemainder = playerInventory.insert(overflow, false, false)",
                    "player.drop(refundRemainder, false)",
                ):
                    self.assertIn(marker, single)

    def test_missing_items_in_saved_blueprint_are_not_silently_discarded(self) -> None:
        for target in TARGETS:
            with self.subTest(target=target):
                source = self.schematic(target)
                self.assertIn("entry.slot < 0 || entry.stack.isEmpty()", source)
                self.assertIn("unavailable/missing item", source)
                self.assertNotIn(".filter(entry -> !entry.stack.isEmpty())", source)
                self.assertIn("Runtime inventory clear requires deferred inventory contents", source)


if __name__ == "__main__":
    unittest.main()
