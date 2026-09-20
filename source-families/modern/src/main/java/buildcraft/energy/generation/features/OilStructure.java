package buildcraft.energy.generation.features;

import java.util.function.Predicate;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.enums.EnumSpring;
import buildcraft.core.BCCoreBlocks;
import buildcraft.core.block.BlockSpring;
import buildcraft.energy.BCEnergyFluids;
import buildcraft.energy.tile.TileSpringOil;
import buildcraft.lib.BCLib;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.VecUtil;
import buildcraft.lib.misc.data.Box;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

public abstract class OilStructure {
    public final Box box;
    public final ReplaceType replaceType;

    public OilStructure(Box containingBox, ReplaceType replaceType) {
        this.box = containingBox;
        this.replaceType = replaceType;
    }

    public final void generate(WorldGenLevel world, Box within) {
        Box intersect = box.getIntersect(within);
        if (intersect != null) {
            generateWithin(world, intersect);
        }
    }

    /** Generates this structure in the world, but only between the given coordinates. */
    protected abstract void generateWithin(WorldGenLevel world, Box intersect);


    protected abstract int countOilBlocks();

    public void setOilIfCanReplace(WorldGenLevel world, BlockPos pos) {
        if (canReplaceForOil(world, pos)) {
            setOil(world, pos);
        }
    }

    public boolean canReplaceForOil(WorldGenLevel world, BlockPos pos) {
        return replaceType.canReplace(world, pos);
    }

    private static FluidState crudeOil() {
        if (BCEnergyFluids.OIL_SOURCE.isEmpty() || !BCEnergyFluids.OIL_SOURCE.get(0).isBound()) {
            return Fluids.EMPTY.defaultFluidState();
        }
        return BCEnergyFluids.OIL_SOURCE.get(0).get().defaultFluidState();
    }

    public static void setOil(WorldGenLevel world, BlockPos pos) {
        FluidState oil = crudeOil();
        if (oil.isEmpty()) {
            return;
        }
        world.setBlock(pos, oil.createLegacyBlock(), Block.UPDATE_CLIENTS);
        world.scheduleTick(pos, oil.getType(), 0);
    }

    public enum ReplaceType {
        ALWAYS {
            @Override
            public boolean canReplace(WorldGenLevel world, BlockPos pos) {
                BlockState state = world.getBlockState(pos);
                // Oil deposits must never punch holes through the world's bedrock floor.
                if (state.is(Blocks.BEDROCK)) {
                    return false;
                }
                if (state.is(BlockTags.LOGS)) {
                    return false;
                }
                ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
                if (key != null && key.getPath().contains("mushroom")) {
                    return false;
                }
                return true;
            }
        },
        IS_FOR_LAKE {
            @Override
            public boolean canReplace(WorldGenLevel world, BlockPos pos) {
                return ALWAYS.canReplace(world, pos);
            }
        };
        public abstract boolean canReplace(WorldGenLevel world, BlockPos pos);
    }

    public static class GenByPredicate extends OilStructure {
        public final Predicate<BlockPos> predicate;

        public GenByPredicate(Box containingBox, ReplaceType replaceType, Predicate<BlockPos> predicate) {
            super(containingBox, replaceType);
            this.predicate = predicate;
        }

        @Override
        protected void generateWithin(WorldGenLevel world, Box intersect) {
            for (BlockPos pos : BlockPos.betweenClosed(intersect.min(), intersect.max())) {
                if (predicate.test(pos)) {
                    setOilIfCanReplace(world, pos);
                }
            }
        }

        @Override
        protected int countOilBlocks() {
            int count = 0;
            for (BlockPos pos : BlockPos.betweenClosed(box.min(), box.max())) {
                if (predicate.test(pos)) {
                    count++;
                }
            }
            return count;
        }
    }

    public static class FlatPattern extends OilStructure {
        private final boolean[][] pattern;
        private final int depth;

        private FlatPattern(Box containingBox, ReplaceType replaceType, boolean[][] pattern, int depth) {
            super(containingBox, replaceType);
            this.pattern = pattern;
            this.depth = depth;
        }

        public static FlatPattern create(BlockPos start, ReplaceType replaceType, boolean[][] pattern, int depth) {
            BlockPos min = start.offset(0, 1 - depth, 0);
            BlockPos max = start.offset(pattern.length - 1, 0, pattern.length == 0 ? 0 : pattern[0].length - 1);
            Box box = new Box(min, max);
            return new FlatPattern(box, replaceType, pattern, depth);
        }

        @Override
        protected void generateWithin(WorldGenLevel world, Box intersect) {
            BlockPos start = box.min();
            for (BlockPos pos : BlockPos.betweenClosed(intersect.min(), intersect.max())) {
                int x = pos.getX() - start.getX();
                int z = pos.getZ() - start.getZ();
                if (pattern[x][z]) {
                    setOilIfCanReplace(world, pos);
                }
            }
        }

        @Override
        protected int countOilBlocks() {
            int count = 0;
            for (int x = 0; x < pattern.length; x++) {
                for (int z = 0; z < pattern[x].length; z++) {
                    if (pattern[x][z]) {
                        count++;
                    }
                }
            }
            return count * depth;
        }
    }

    public static class PatternTerrainHeight extends OilStructure {
        private final boolean[][] pattern;
        private final int depth;

        private PatternTerrainHeight(Box containingBox, ReplaceType replaceType, boolean[][] pattern, int depth) {
            super(containingBox, replaceType);
            this.pattern = pattern;
            this.depth = depth;
        }

        public static PatternTerrainHeight create(BlockPos.MutableBlockPos start, ReplaceType replaceType, boolean[][] pattern,
            int depth) {
            int minY = OilGenerator.bottomY;
            int maxY = minY + OilGenerator.worldHeight - 1;
            int maxX = start.getX() + Math.max(0, pattern.length - 1);
            int maxZ = start.getZ() + (pattern.length == 0 ? 0 : Math.max(0, pattern[0].length - 1));
            BlockPos min = new BlockPos(start.getX(), minY, start.getZ());
            BlockPos max = new BlockPos(maxX, maxY, maxZ);
            Box box = new Box(min, max);
            return new PatternTerrainHeight(box, replaceType, pattern, depth);
        }

        @Override
        protected void generateWithin(WorldGenLevel world, Box intersect) {
            MutableBlockPos pos = new MutableBlockPos();
            for (int x = intersect.min().getX(); x <= intersect.max().getX(); x++) {
                int px = x - box.min().getX();

                for (int z = intersect.min().getZ(); z <= intersect.max().getZ(); z++) {
                    int pz = z - box.min().getZ();

                    if (pattern[px][pz]) {
                        BlockPos.MutableBlockPos upper = world.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos.set(x, 0, z)).mutable().move(0, -1, 0);
                        int h = upper.getY();
                        if (canReplaceForOil(world, upper)) {
                            for (int y = 0; y < 5; y++) {
                                world.setBlock(upper.setY(y+h), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                            }
                            for (int y = 0; y < depth; y++) {
                                setOilIfCanReplace(world, upper.setY(h-y));
                            }
                        }
                    }
                }
            }
        }

        @Override
        protected int countOilBlocks() {
            int count = 0;
            for (int x = 0; x < pattern.length; x++) {
                for (int z = 0; z < pattern[x].length; z++) {
                    if (pattern[x][z]) {
                        count++;
                    }
                }
            }
            return count * depth;
        }
    }

    public static class Spout extends OilStructure {
        public final BlockPos start;
        public final int radius;
        public final int height;
        private int count = 0;

        public Spout(BlockPos start, ReplaceType replaceType, int radius, int height) {
            super(createBox(start), replaceType);
            this.start = start;
            this.radius = radius;
            this.height = height;
        }

        private static Box createBox(BlockPos start) {
            // Only a block 1 x level.getHeight()(default 384) x 1 -- that way we area only called once.
            int maxY = OilGenerator.bottomY + OilGenerator.worldHeight - 1;
            return new Box(start, VecUtil.replaceValue(start, Axis.Y, maxY));
        }

        @Override
        protected void generateWithin(WorldGenLevel world, Box intersect) {
            count = 0;
            int topY = world.getHeight(Heightmap.Types.WORLD_SURFACE, start.getX(), start.getZ());
            BlockPos worldTop = new BlockPos(start.getX(), topY, start.getZ());
            for (int y = topY; y >= start.getY(); y--) {
                worldTop = worldTop.below();
                BlockState state = world.getBlockState(worldTop);
                if (state.isAir()) {
                    continue;
                }
                if (BlockUtil.getFluidWithoutFlowing(state) != Fluids.EMPTY) {
                    break;
                }
                if (state.blocksMotion()) {
                    break;
                }
            }
            OilStructure tubeY = OilGenerator.createTube(start, worldTop.getY() - start.getY(), radius, Axis.Y);
            tubeY.generate(world, tubeY.box);
            count += tubeY.countOilBlocks();
            BlockPos base = worldTop;
            for (int r = radius; r >= 0; r--) {
                // BCLog.logger.info(" - " + base + " = " + r);
                OilStructure struct = OilGenerator.createTube(base, height, r, Axis.Y);
                struct.generate(world, struct.box);
                base = base.offset(0, height, 0);
                count += struct.countOilBlocks();
            }
        }

        @Override
        protected int countOilBlocks() {
            if (count == 0) {
                throw new IllegalStateException("Called countOilBlocks before calling generateWithin!");
            }
            return count;
        }
    }

    public static class Spring extends OilStructure {
        public final BlockPos pos;

        public Spring(BlockPos pos) {
            super(new Box(pos, pos), ReplaceType.ALWAYS);
            this.pos = pos;
        }

        @Override
        protected void generateWithin(WorldGenLevel world, Box intersect) {
            // NO-OP (this one is called separately)
        }

        @Override
        protected int countOilBlocks() {
            return 0;
        }

        public void generate(WorldGenLevel world, int count) {
            // Oil springs must not replace the bedrock floor; move upward to the first non-bedrock block.
            BlockPos springPos = pos;
            int maxSpringY = Math.min(pos.getY() + 16, world.getMaxBuildHeight() - 1);
            while (springPos.getY() < maxSpringY && world.getBlockState(springPos).is(Blocks.BEDROCK)) {
                springPos = springPos.above();
            }
            if (world.getBlockState(springPos).is(Blocks.BEDROCK)) {
                BCLog.logger.warn("[energy.gen.oil] Could not find a non-bedrock position for oil spring at " + pos);
                return;
            }

            BlockState state = BCCoreBlocks.SPRING.get().defaultBlockState();
            state = state.setValue(BlockSpring.SPRING_TYPE, EnumSpring.OIL);
            //BCLog.logger.debug("OilGenStruecutre:1 generate spring for "+springPos);
            world.setBlock(springPos, state, 2);
            BlockEntity tile = world.getBlockEntity(springPos);
            TileSpringOil spring;
            if (tile instanceof TileSpringOil) {
                spring = (TileSpringOil) tile;
                spring.totalSources = count;
            } else {
                BCLog.logger.warn("[energy.gen.oil] Setting the blockstate didn't also set the tile at " + springPos);
                spring = new TileSpringOil(springPos, state);
                ServerLevel level = world.getLevel();
                spring.setLevel(level);
                level.setBlockEntity(spring);
            }
            spring.totalSources = count;
            if (BCLib.DEV) {
                BCLog.logger.info("[energy.gen.oil] Generated TileSpringOil as " + System.identityHashCode(tile));
            }
        }
    }
}
