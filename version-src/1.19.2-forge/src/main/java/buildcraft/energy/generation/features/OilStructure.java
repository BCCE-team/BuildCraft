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
    /**
     * Surface oil is generated around the well's X/Z origin. Do not let sparse tendril noise
     * climb a cliff or mountain far above that origin: isolated high cells become extra natural
     * oil sources and can create huge downhill cascades on terrain-overhaul worlds.
     */
    private static final int MAX_SURFACE_RISE_ABOVE_SPOT = 4;

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

    private static FluidState worldgenOil() {
        if (BCEnergyFluids.SPOUT_OIL_SOURCE == null || !BCEnergyFluids.SPOUT_OIL_SOURCE.isBound()) {
            return Fluids.EMPTY.defaultFluidState();
        }
        return BCEnergyFluids.SPOUT_OIL_SOURCE.get().defaultFluidState();
    }

    public static void setOil(WorldGenLevel world, BlockPos pos) {
        FluidState oil = worldgenOil();
        if (oil.isEmpty()) {
            return;
        }
        world.setBlock(pos, oil.createLegacyBlock(), 2);
        world.scheduleTick(pos, oil.getType(), 0);
    }

    /**
     * Returns whether oil worldgen may replace this block. This is intentionally conservative:
     * unknown/constructed blocks are protected by default, while normal terrain, replaceable
     * vegetation and fluids remain eligible. That keeps deposits from eating player builds or
     * generated structures from other mods.
     */
    private static boolean canReplaceNaturalTerrain(WorldGenLevel world, BlockPos pos, BlockState state) {
        // Never touch blocks that are creative-only/unbreakable (bedrock, end portal frames,
        // barriers, command/structure blocks, etc.) or blocks carrying persistent state.
        if (state.getDestroySpeed(world, pos) < 0.0F || state.hasBlockEntity()) {
            return false;
        }

        // Trees and processed wood are deliberately preserved even though logs/leaves can be
        // naturally generated: an oil deposit should flow around them instead of deleting them.
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.PLANKS)) {
            return false;
        }

        ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null) {
            // Unknown/unregistered blocks are safest to treat as structure/player content.
            return false;
        }
        String path = key.getPath();
        if (isProtectedConstructionPath(path)) {
            return false;
        }

        if (state.isAir()) {
            return true;
        }

        // Existing natural fluids may be displaced by an oil deposit.
        if (!state.getFluidState().isEmpty()) {
            return true;
        }

        // Only known natural decoration is allowed through the generic replaceable-block path.
        // A modded/player block being replaceable is not, by itself, permission to destroy it.
        if (state.getMaterial().isReplaceable() && isNaturalDecorationPath(path)) {
            return true;
        }

        // Everything solid is denied unless it looks like ordinary geological terrain. This
        // makes modded machines/decorative blocks safe by default while still accepting common
        // modded ores and terrain blocks whose registry names follow vanilla conventions.
        return isNaturalTerrainPath(path);
    }

    private static boolean isProtectedConstructionPath(String path) {
        return path.contains("cobble")
            || path.contains("prismarine")
            || path.equals("sea_lantern")
            || path.equals("conduit")
            || path.contains("brick")
            || path.contains("planks")
            || path.contains("leaves")
            || path.contains("glass")
            || path.contains("concrete")
            || path.contains("glazed_terracotta")
            || path.contains("wool")
            || path.contains("carpet")
            || path.contains("purpur")
            || path.contains("polished")
            || path.contains("chiseled")
            || path.contains("_tiles")
            || path.contains("tiled_")
            || path.startsWith("cut_")
            || path.contains("_cut_")
            || path.equals("smooth_stone")
            || path.contains("quartz_block")
            || path.contains("quartz_pillar")
            || path.endsWith("_slab")
            || path.endsWith("_stairs")
            || path.endsWith("_wall")
            || path.endsWith("_fence")
            || path.endsWith("_fence_gate")
            || path.endsWith("_door")
            || path.endsWith("_trapdoor")
            || path.endsWith("_button")
            || path.endsWith("_pressure_plate")
            || path.endsWith("_sign")
            || path.endsWith("_hanging_sign")
            || path.contains("torch")
            || path.contains("rail")
            || path.equals("redstone_wire")
            || path.equals("redstone_block")
            || path.equals("redstone_lamp")
            || path.contains("repeater")
            || path.contains("comparator")
            || path.contains("lever")
            || path.contains("tripwire")
            || path.contains("ladder")
            || path.contains("lantern")
            || path.equals("chain")
            || path.contains("iron_bars")
            || path.contains("candle")
            || path.contains("scaffolding")
            || path.contains("mushroom");
    }

    private static boolean isNaturalDecorationPath(String path) {
        return path.equals("grass")
            || path.equals("short_grass")
            || path.equals("tall_grass")
            || path.equals("fern")
            || path.equals("large_fern")
            || path.equals("dead_bush")
            || path.equals("lily_pad")
            || path.equals("sugar_cane")
            || path.equals("bamboo")
            || path.equals("bamboo_sapling")
            || path.equals("cactus")
            || path.equals("seagrass")
            || path.equals("tall_seagrass")
            || path.equals("kelp")
            || path.equals("kelp_plant")
            || path.equals("vine")
            || path.equals("glow_lichen")
            || path.equals("hanging_roots")
            || path.equals("nether_sprouts")
            || path.equals("crimson_roots")
            || path.equals("warped_roots")
            || path.equals("weeping_vines")
            || path.equals("weeping_vines_plant")
            || path.equals("twisting_vines")
            || path.equals("twisting_vines_plant")
            || path.equals("cave_vines")
            || path.equals("cave_vines_plant")
            || path.equals("small_dripleaf")
            || path.equals("big_dripleaf")
            || path.equals("big_dripleaf_stem")
            || path.equals("moss_carpet")
            || path.equals("snow")
            || path.endsWith("_flower")
            || path.endsWith("_tulip")
            || path.endsWith("_orchid")
            || path.endsWith("_bluet")
            || path.endsWith("_daisy")
            || path.endsWith("_cornflower")
            || path.endsWith("_rose")
            || path.endsWith("_sunflower")
            || path.endsWith("_lilac")
            || path.endsWith("_peony")
            || path.endsWith("_sapling");
    }

    private static boolean isNaturalTerrainPath(String path) {
        if (path.endsWith("_ore")) {
            return true;
        }
        if (path.endsWith("_terracotta") && !path.contains("glazed_terracotta")) {
            return true;
        }
        if (path.endsWith("_stone") && !path.equals("smooth_stone") && !path.equals("lodestone")) {
            return true;
        }
        if (path.endsWith("_dirt") || path.endsWith("_sand") || path.endsWith("_gravel")
            || path.endsWith("_clay") || path.endsWith("_tuff") || path.endsWith("_basalt")
            || path.endsWith("_nylium") || path.endsWith("_soil") || path.endsWith("_ice")
            || path.endsWith("_snow")) {
            return true;
        }
        return path.equals("stone")
            || path.equals("deepslate")
            || path.equals("granite")
            || path.equals("diorite")
            || path.equals("andesite")
            || path.equals("tuff")
            || path.equals("calcite")
            || path.equals("dripstone_block")
            || path.equals("pointed_dripstone")
            || path.equals("dirt")
            || path.equals("grass_block")
            || path.equals("gravel")
            || path.equals("clay")
            || path.equals("sand")
            || path.equals("red_sand")
            || path.equals("sandstone")
            || path.equals("red_sandstone")
            || path.equals("terracotta")
            || path.equals("snow")
            || path.equals("snow_block")
            || path.equals("powder_snow")
            || path.equals("ice")
            || path.equals("packed_ice")
            || path.equals("blue_ice")
            || path.equals("mud")
            || path.equals("moss_block")
            || path.equals("netherrack")
            || path.equals("basalt")
            || path.equals("smooth_basalt")
            || path.equals("blackstone")
            || path.equals("soul_sand")
            || path.equals("soul_soil")
            || path.equals("crimson_nylium")
            || path.equals("warped_nylium")
            || path.equals("magma_block")
            || path.equals("end_stone")
            || path.equals("glowstone")
            || path.equals("ancient_debris")
            || path.equals("sculk")
            || path.equals("sculk_vein");
    }

    private static int getGeneratorSurfaceY(WorldGenLevel world, int x, int z) {
        ServerLevel level = world.getLevel();
        return level.getChunkSource().getGenerator().getBaseHeight(
            x,
            z,
            Heightmap.Types.WORLD_SURFACE_WG,
            level,
            level.getChunkSource().randomState()
        ) - 1;
    }

    private static boolean canClearSurfaceColumn(WorldGenLevel world, int x, int baseY, int z) {
        for (int offsetY = 0; offsetY < 5; offsetY++) {
            BlockPos checkPos = new BlockPos(x, baseY + offsetY, z);
            BlockState state = world.getBlockState(checkPos);
            if (!canReplaceNaturalTerrain(world, checkPos, state)) {
                return false;
            }
        }
        return true;
    }

    public enum ReplaceType {
        ALWAYS {
            @Override
            public boolean canReplace(WorldGenLevel world, BlockPos pos) {
                return canReplaceNaturalTerrain(world, pos, world.getBlockState(pos));
            }
        },
        IS_FOR_LAKE {
            @Override
            public boolean canReplace(WorldGenLevel world, BlockPos pos) {
                return canReplaceNaturalTerrain(world, pos, world.getBlockState(pos));
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
            int centerX = box.min().getX() + pattern.length / 2;
            int centerZ = box.min().getZ() + (pattern.length == 0 ? 0 : pattern[0].length / 2);
            int spotSurfaceY = getGeneratorSurfaceY(world, centerX, centerZ);
            int maxSurfaceY = spotSurfaceY + MAX_SURFACE_RISE_ABOVE_SPOT;

            for (int x = intersect.min().getX(); x <= intersect.max().getX(); x++) {
                int px = x - box.min().getX();

                for (int z = intersect.min().getZ(); z <= intersect.max().getZ(); z++) {
                    int pz = z - box.min().getZ();

                    if (pattern[px][pz]) {
                        BlockPos.MutableBlockPos upper = world.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos.set(x, 0, z)).mutable().move(0, -1, 0);
                        int h = upper.getY();
                        if (h > maxSurfaceY) {
                            continue;
                        }
                        if (canReplaceForOil(world, upper) && canClearSurfaceColumn(world, x, h, z)) {
                            for (int y = 0; y < 5; y++) {
                                world.setBlock(upper.setY(y + h), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                            }
                            for (int y = 0; y < depth; y++) {
                                setOilIfCanReplace(world, upper.setY(h - y));
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
            // One column from the underground deposit to the world's actual top build coordinate.
            int maxY = OilGenerator.bottomY + OilGenerator.worldHeight - 1;
            return new Box(start, VecUtil.replaceValue(start, Axis.Y, maxY));
        }

        @Override
        protected void generateWithin(WorldGenLevel world, Box intersect) {
            count = 0;
            // Query the heightmap already available to the current worldgen region. Calling getChunk(start) here
            // could request a neighbouring chunk while this chunk was still generating and form a dependency cycle.
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, start.getX(), start.getZ());
            BlockPos worldTop = new BlockPos(start.getX(), surfaceY, start.getZ());
            for (int y = surfaceY; y >= start.getY(); y--) {
                worldTop = worldTop.below();
                BlockState state = world.getBlockState(worldTop);
                if (state.isAir()) {
                    continue;
                }
                if (BlockUtil.getFluidWithoutFlowing(state) != Fluids.EMPTY) {
                    break;
                }
                if (state.getMaterial().blocksMotion()) {
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
