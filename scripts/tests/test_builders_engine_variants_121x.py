#!/usr/bin/env python3
"""Check that blueprint engine previews and placement costs preserve all five variants.

Uses actual Stonecutter-materialized source for the cost resolver and compiles it
against narrow Java doubles. Rendering contracts remain source-level checks; the
real 1.21.1/1.21.11 client still needs an in-game smoke test.
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java

BASE = 'src/main/java/buildcraft/'
ENGINE_ITEM = BASE + 'core/EngineItemVariants.java'
EXTRACTOR = BASE + 'builders/snapshot/RequiredExtractorItemFromBlock.java'
R_1211 = BASE + 'builders/client/render/BlueprintGuiRenderer.java'
S_1211 = BASE + 'builders/client/render/BlueprintBlockEntityScene.java'
R_12111 = BASE + 'builders/client/render/pip/BlueprintPipRenderer.java'


class EngineVariantTests(unittest.TestCase):
    def test_1211_world_engine_renders_both_model_and_block_entity(self):
        code = read_materialized_java('1.21.1-neoforge', R_1211)
        scene = read_materialized_java('1.21.1-neoforge', S_1211)
        self.assertIn('state.getBlock() instanceof BlockPipeHolder;', code)
        self.assertIn('state.hasBlockEntity() && scene != null', code)
        self.assertIn('blockEntityRenderer.renderItem(be, pose, buffers,', code)
        self.assertIn('be instanceof TileEngineBase_BC8 engine', code)
        self.assertIn('engine.getModelData()', code)
        self.assertIn('ENGINE_MODEL_FACING', code)
        self.assertIn('renderSingleBlock(state, pose, buffers,', code)
        self.assertIn('return state.getBlock() instanceof BlockPipeHolder;', scene)
        self.assertNotIn('state.getBlock() instanceof BlockEngineBase_BC8\n            ||', scene)

    def test_both_renderers_use_type_aware_item_fallback(self):
        renderer1211 = read_materialized_java('1.21.1-neoforge', R_1211)
        renderer12111 = read_materialized_java('1.21.11-neoforge', R_12111)
        for renderer in (renderer1211, renderer12111):
            with self.subTest(which=renderer.split('class ', 1)[1][:40]):
                self.assertIn('EngineItemVariants.fromState(state)', renderer)
                self.assertIn('state.getBlock() instanceof BlockEngine_BC8', renderer)

    def test_cost_extractor_precedes_both_fallbacks(self):
        for target in ('1.21.1-neoforge', '1.21.11-neoforge'):
            source = read_materialized_java(target, EXTRACTOR)
            self.assertLess(source.index('EngineItemVariants.fromState(blockState)'),
                            source.index('new ItemStack(blockState.getBlock().asItem())')
                            if 'new ItemStack(blockState.getBlock().asItem())' in source
                            else source.index('getCloneItemStack(level, BlockPos.ZERO, blockState)'))
            self.assertIn('throw new IllegalStateException("Engine item is not registered', source)

    def test_engine_variant_costs_compile_and_run_for_both_targets(self):
        stubs = {
            'javax/annotation/Nonnull.java': 'package javax.annotation; public @interface Nonnull {}',
            'javax/annotation/Nullable.java': 'package javax.annotation; public @interface Nullable {}',
            'net/minecraft/core/BlockPos.java': 'package net.minecraft.core; public class BlockPos { public static final BlockPos ZERO = new BlockPos(); }',
            'net/minecraft/nbt/CompoundTag.java': 'package net.minecraft.nbt; public class CompoundTag {}',
            'net/minecraft/world/level/Level.java': 'package net.minecraft.world.level; public class Level {}',
            'net/minecraft/world/level/block/state/properties/Property.java': 'package net.minecraft.world.level.block.state.properties; public class Property<T> {}',
            'net/minecraft/world/item/Item.java': '''package net.minecraft.world.item; public class Item {
                public final String id; public Item(String id) { this.id=id; }
            }''',
            'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item; public class ItemStack {
                public static final ItemStack EMPTY = new ItemStack((Item)null);
                public final Item item; public ItemStack(Item item) {this.item=item;}
                public boolean isEmpty() { return item==null; }
            }''',
            'net/minecraft/world/level/block/Block.java': '''package net.minecraft.world.level.block;
                import net.minecraft.world.item.*;
                import net.minecraft.world.level.Level;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.block.state.BlockState;
                public class Block {
                    // Simulate the observed ambiguity: every variant shares one block,
                    // and the default item incorrectly resolves to FE.
                    public Item asItem() {return new Item("FE");}
                    public ItemStack getCloneItemStack(Level world, BlockPos pos, BlockState state) {
                        return new ItemStack(asItem());
                    }
                }''',
            'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
                import net.minecraft.world.level.block.Block;
                import net.minecraft.world.level.block.state.properties.Property;
                public class BlockState {
                    private final Block block; private final Object variant;
                    public BlockState(Block block, Object variant) {this.block=block; this.variant=variant;}
                    public Block getBlock() {return block;}
                    @SuppressWarnings("unchecked") public <T> T getValue(Property<T> p) {return (T)variant;}
                }''',
            'buildcraft/lib/internal/enums/EnumEngineType.java': '''package buildcraft.lib.internal.enums;
                public enum EnumEngineType { WOOD, STONE, IRON, CREATIVE, FE }''',
            'buildcraft/lib/item/MultiBlockItem.java': '''package buildcraft.lib.item;
                import net.minecraft.world.item.Item;
                public class MultiBlockItem<T> extends Item {
                    public MultiBlockItem(String id) {super(id);}
                }''',
            'buildcraft/core/BCCoreItems.java': '''package buildcraft.core;
                import java.util.EnumMap;
                import buildcraft.lib.item.MultiBlockItem;
                import buildcraft.lib.internal.enums.EnumEngineType;
                public class BCCoreItems {
                    public static final EnumMap<EnumEngineType, MultiBlockItem<EnumEngineType>> ENGINE_ITEM_MAP =
                        new EnumMap<>(EnumEngineType.class);
                }''',
            'buildcraft/core/block/BlockEngine_BC8.java': '''package buildcraft.core.block;
                import buildcraft.lib.internal.enums.EnumEngineType;
                import net.minecraft.world.level.block.Block;
                import net.minecraft.world.level.block.state.properties.Property;
                public class BlockEngine_BC8 extends Block {
                    private final Property<EnumEngineType> type = new Property<>();
                    public Property<EnumEngineType> getEngineProperty() {return type;}
                }''',
            'buildcraft/builders/snapshot/RequiredExtractor.java': '''package buildcraft.builders.snapshot;
                import java.util.List;
                import javax.annotation.*;
                import net.minecraft.world.item.ItemStack;
                import net.minecraft.world.level.Level;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraft.nbt.CompoundTag;
                public abstract class RequiredExtractor {
                    public abstract List<ItemStack> extractItemsFromBlock(BlockState s, CompoundTag tag, Level level);
                }''',
            'EngineVariantsProbe.java': '''import buildcraft.core.*;
                import buildcraft.core.block.BlockEngine_BC8;
                import buildcraft.builders.snapshot.RequiredExtractorItemFromBlock;
                import buildcraft.lib.internal.enums.EnumEngineType;
                import buildcraft.lib.item.MultiBlockItem;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraft.world.level.Level;
                public class EngineVariantsProbe {
                    public static void main(String[] args) {
                        for (EnumEngineType type : EnumEngineType.values()) {
                            BCCoreItems.ENGINE_ITEM_MAP.put(type, new MultiBlockItem<>(type.name()));
                        }
                        var extractor = new RequiredExtractorItemFromBlock();
                        for (EnumEngineType type : EnumEngineType.values()) {
                            var state = new BlockState(new BlockEngine_BC8(), type);
                            var cost = extractor.extractItemsFromBlock(state, null, new Level());
                            if (cost.size() != 1 || cost.get(0).isEmpty()
                                || !cost.get(0).item.id.equals(type.name())) {
                                throw new AssertionError("Wrong engine variant cost: " + type);
                            }
                            if (!EngineItemVariants.fromState(state).item.id.equals(type.name())) {
                                throw new AssertionError("Wrong engine preview item: " + type);
                            }
                        }
                        System.out.println("5/5 engine variants passed");
                    }
                }''',
        }
        for target in ('1.21.1-neoforge', '1.21.11-neoforge'):
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='bcce-engine-cost-') as tmp:
                directory = Path(tmp)
                for path, text in stubs.items():
                    dest = directory / path
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_text(text)
                for logical in (ENGINE_ITEM, EXTRACTOR):
                    dest = directory / logical.removeprefix('src/main/java/')
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_text(read_materialized_java(target, logical))
                files = [str(p) for p in directory.rglob('*.java')]
                classes = directory / 'classes'
                classes.mkdir()
                built = subprocess.run(['javac', '-d', str(classes), *files],
                                       text=True, capture_output=True, timeout=40)
                self.assertEqual(built.returncode, 0, built.stderr)
                ran = subprocess.run(['java', '-cp', str(classes), 'EngineVariantsProbe'],
                                     text=True, capture_output=True, timeout=15)
                self.assertEqual(ran.returncode, 0, ran.stderr)
                self.assertIn('5/5 engine variants passed', ran.stdout)


if __name__ == '__main__':
    unittest.main()
