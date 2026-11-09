#!/usr/bin/env python3
"""Keep live Quarry GameTest scenarios isolated from adjacent fluid fixtures."""
from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
import sys
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import _materialize_text_file, resolve_effective_source

FILE = 'src/gametest/java/buildcraft/gametest/SystemQuarryMiningGameTests.java'
TARGETS = (
    '1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
    '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
)


class QuarryMiningFixtureTests(unittest.TestCase):
    def test_dry_perimeter_is_inside_template_but_outside_mining_and_frame(self):
        boundary = {(x, 0, z) for x in range(2, 6) for z in range(1, 6)
                    if x in (2, 5) or z in (1, 5)}
        mining = {(x, 0, z) for x in range(3, 5) for z in range(2, 5)}
        frame = {(x, y, z) for x in range(2, 6) for y in range(1, 3)
                 for z in range(1, 6)}
        self.assertEqual(14, len(boundary))
        self.assertTrue(boundary.isdisjoint(mining))
        self.assertTrue(boundary.isdisjoint(frame))
        self.assertTrue(all(0 <= x < 7 and 0 <= y < 3 and 0 <= z < 7 for x, y, z in boundary))
        self.assertIn((3, 0, 3), mining)
        self.assertIn((4, 0, 3), mining)

    def test_both_loader_fixtures_keep_dry_boundary_and_strict_assertions(self):
        for loader in ('forge', 'neoforge'):
            with self.subTest(loader=loader):
                path = ROOT / f'source-platforms/{loader}/{FILE}'
                code = path.read_text(encoding='utf-8')
                self.assertIn('if (x == 2 || x == 5 || z == 1 || z == 5)', code)
                self.assertIn('helper.setBlock(new BlockPos(x, 0, z), Blocks.GLASS.defaultBlockState());', code)
                self.assertIn('helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR.defaultBlockState());', code)
                self.assertIn('helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR.defaultBlockState());', code)
                self.assertIn('rig.dryBoundaryIntact()', code)
                self.assertIn('rig.quarry.hasWork()', code)
                self.assertIn('rig.isMined(helper, FIRST)\n                && rig.isMined(helper, SECOND)', code)
                self.assertIn('PipeGameTestSupport.countItem(rig.warehouse, Items.COBBLESTONE) == 2', code)
                self.assertIn('helper.getLevel().getBlockState(helper.absolutePos(PROTECTED)).is(Blocks.BEDROCK)', code)
                self.assertIn('state.getFluidState()', code)
                self.assertIn('collision.getOwner()', code)
                self.assertIn('PipeGameTestSupport.countItem(warehouse, Items.COBBLESTONE)', code)

    def test_mined_cell_predicate_accepts_refill_but_rejects_foreign_drill_and_solid(self):
        # Run the actual method extracted from each loader's GameTest class,
        # not a Python copy of its semantics. The 1.20.1 server returned lava
        # for an already mined block while the warehouse contained both drops.
        for loader in ('forge', 'neoforge'):
            with self.subTest(loader=loader), tempfile.TemporaryDirectory(prefix='quarry-mined-cell-') as temp:
                src = (ROOT / f'source-platforms/{loader}/{FILE}').read_text('utf-8')
                signature = 'boolean isMined(GameTestHelper helper, BlockPos relative)'
                start = src.index(signature)
                opening = src.index('{', start)
                depth, stop = 1, opening + 1
                while depth:
                    depth += (src[stop] == '{') - (src[stop] == '}')
                    stop += 1
                method = src[start:stop]
                self.assertIn('!state.getFluidState().isEmpty()', method)
                self.assertLess(method.index('state.is(BCBuildersBlocks.QUARRY_DRILL_COLLISION.get())'),
                                method.index('!state.getFluidState().isEmpty()'))
                java_source = r'''
import java.util.function.Supplier;
public class QuarryMinedCellProbe {
    static class BlockPos {
        final int id;
        BlockPos(int id) { this.id = id; }
        public boolean equals(Object other) { return other instanceof BlockPos pos && pos.id == id; }
        public int hashCode() { return id; }
    }
    static class Block {}
    static class Blocks {
        static final Block AIR = new Block();
        static final Block STONE = new Block();
        static final Block GLASS = new Block();
        static final Block LAVA = new Block();
        static final Block WATER = new Block();
        static final Block DRILL = new Block();
    }
    static class BCBuildersBlocks {
        static final Supplier<Block> QUARRY_DRILL_COLLISION = () -> Blocks.DRILL;
    }
    static class FluidState {
        final boolean empty;
        FluidState(boolean empty) { this.empty = empty; }
        boolean isEmpty() { return empty; }
    }
    static class BlockState {
        final Block block;
        final FluidState fluid;
        BlockState(Block block, boolean fluid) {
            this.block = block;
            this.fluid = new FluidState(!fluid);
        }
        boolean isAir() { return block == Blocks.AIR; }
        boolean is(Block other) { return block == other; }
        FluidState getFluidState() { return fluid; }
    }
    static class BlockEntity {}
    static class TileQuarryDrillCollision extends BlockEntity {
        final BlockPos owner;
        TileQuarryDrillCollision(BlockPos owner) { this.owner = owner; }
        BlockPos getOwner() { return owner; }
    }
    static class TileQuarry {
        BlockPos getBlockPos() { return new BlockPos(1); }
    }
    static class Level {
        BlockState state;
        BlockEntity blockEntity;
        BlockState getBlockState(BlockPos pos) { return state; }
        BlockEntity getBlockEntity(BlockPos pos) { return blockEntity; }
    }
    static class GameTestHelper {
        final Level level;
        GameTestHelper(Level level) { this.level = level; }
        Level getLevel() { return level; }
        BlockPos absolutePos(BlockPos pos) { return pos; }
    }
    static class Rig {
        final TileQuarry quarry = new TileQuarry();
        /*REAL_METHOD*/
    }
    static void check(boolean actual, boolean expected) {
        if (actual != expected) throw new AssertionError("unexpected mined predicate: " + actual);
    }
    public static void main(String[] args) {
        Level level = new Level();
        GameTestHelper helper = new GameTestHelper(level);
        Rig rig = new Rig();
        BlockPos pos = new BlockPos(3);
        level.state = new BlockState(Blocks.STONE, false);
        check(rig.isMined(helper, pos), false);
        level.state = new BlockState(Blocks.GLASS, false);
        check(rig.isMined(helper, pos), false);
        level.state = new BlockState(Blocks.AIR, false);
        check(rig.isMined(helper, pos), true);
        level.state = new BlockState(Blocks.LAVA, true);
        check(rig.isMined(helper, pos), true);
        level.state = new BlockState(Blocks.WATER, true);
        check(rig.isMined(helper, pos), true);
        level.state = new BlockState(Blocks.DRILL, false);
        level.blockEntity = new TileQuarryDrillCollision(new BlockPos(1));
        check(rig.isMined(helper, pos), true);
        level.state = new BlockState(Blocks.DRILL, true); // foreign waterlogged drill
        level.blockEntity = new TileQuarryDrillCollision(new BlockPos(2));
        check(rig.isMined(helper, pos), false);
        level.blockEntity = null;
        check(rig.isMined(helper, pos), false);
    }
}
'''.replace('/*REAL_METHOD*/', method)
                source_file = Path(temp) / 'QuarryMinedCellProbe.java'
                source_file.write_text(java_source, encoding='utf-8')
                subprocess.run(['javac', str(source_file)], capture_output=True, text=True, check=True)
                subprocess.run(['java', '-cp', temp, 'QuarryMinedCellProbe'], capture_output=True, text=True, check=True)

    def test_mined_cell_guard_is_also_balanced_by_exact_item_export(self):
        for loader in ('forge', 'neoforge'):
            src = (ROOT / f'source-platforms/{loader}/{FILE}').read_text(encoding='utf-8')
            self.assertIn('rig.isMined(helper, FIRST)\n                && rig.isMined(helper, SECOND)', src)
            self.assertIn('PipeGameTestSupport.countItem(rig.warehouse, Items.COBBLESTONE) == 2', src)
            self.assertIn('Quarry generated drops while no block was mined', src)
            self.assertIn('Quarry lost its pending mining work during the MJ outage', src)

    def test_all_effective_targets_resolve_new_fixture(self):
        props = load_properties()
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='bcce-mining-') as temporary:
                layout = target_layout(target, props)
                src = resolve_effective_source(layout, props, FILE)
                self.assertIsNotNone(src, target)
                dest = Path(temporary) / 'SystemQuarryMiningGameTests.java'
                _materialize_text_file(src, dest, logical_relative=FILE,
                    minecraft=props[f'target.{target}.deps.minecraft'],
                    family=layout.family, platform=layout.platform, preprocess=True)
                effective = dest.read_text(encoding='utf-8')
                self.assertIn('rig.dryBoundaryIntact()', effective)
                self.assertIn('Quarry lost its pending mining work', effective)
                self.assertIn('collisionOwner=', effective)
                self.assertIn('return !state.getFluidState().isEmpty();', effective)
                self.assertNotIn('import net.minecraftforge', effective) if target.endswith('-neoforge') else self.assertNotIn('import net.neoforged', effective)


if __name__ == '__main__':
    unittest.main()
