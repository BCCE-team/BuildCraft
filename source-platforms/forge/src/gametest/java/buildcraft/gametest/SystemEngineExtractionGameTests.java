package buildcraft.gametest;

import buildcraft.core.BCCoreBlocks;
import buildcraft.core.blockEntity.TileEngineRedstone_BC8;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.enums.EnumEngineType;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDirectional;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDiamond;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDiamondItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraft.nbt.CompoundTag;

/**
 * Full powered item-transport rigs: the engine, piston cadence, Wooden Pipe,
 * real inventory and sorting/output pipes all run on the server's normal tick.
 * No direct receivePower() or injectItem() calls are made by these tests.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemEngineExtractionGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos ENGINE_POS = new BlockPos(2, 0, 3);
    private static final BlockPos REDSTONE_POS = new BlockPos(2, 0, 2);
    private static final BlockPos SOURCE_POS = new BlockPos(1, 1, 3);
    private static final BlockPos WOOD_POS = new BlockPos(2, 1, 3);

    private SystemEngineExtractionGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1050)
    public static void engineStrokeExtractionPausesAndRestartsWithRealRedstone(GameTestHelper helper) {
        ChestBlockEntity source = chest(helper, SOURCE_POS);
        ItemStack original = new ItemStack(Items.APPLE, 20);
        mark(original, "pulsed_engine", 6_221_000L);
        source.setItem(0, original.copy());
        source.setChanged();
        TilePipeHolder extractor = PipeGameTestSupport.placePipe(helper, WOOD_POS, BCTransportPipes.woodItem);
        TilePipeHolder line = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleItem);
        ChestBlockEntity destination = chest(helper, new BlockPos(4, 1, 3));
        TileEngineRedstone_BC8 engine = powerEngine(helper, extractor);
        final int[] afterPowerOff = { -1 };

        helper.runAfterDelay(30, () -> {
            require(helper, engine.isRedstonePowered, "engine did not detect real redstone block");
            require(helper, engine.getCurrentFacing() == Direction.UP,
                "engine did not orient itself toward the actual wooden-pipe MJ receiver");
            require(helper, extractor.getPipe().isConnected(Direction.WEST)
                && extractor.getPipe().isConnected(Direction.EAST)
                && line.getPipe().isConnected(Direction.EAST), "item transport rig is disconnected");
        });
        helper.runAfterDelay(250, () -> {
            int extracted = 20 - count(source, original);
            require(helper, extracted > 0 && extracted <= 20,
                "redstone engine completed no actual wooden-pipe extraction strokes");
            helper.setBlock(REDSTONE_POS, Blocks.AIR.defaultBlockState());
        });
        // Finish the last in-flight piston stroke before demanding stationary input count.
        helper.runAfterDelay(410, () -> {
            require(helper, !engine.isRedstonePowered, "engine did not observe redstone shutoff");
            afterPowerOff[0] = count(source, original);
        });
        helper.runAfterDelay(510, () -> {
            require(helper, count(source, original) == afterPowerOff[0],
                "disabled engine kept extracting from a real inventory");
            helper.setBlock(REDSTONE_POS, Blocks.REDSTONE_BLOCK.defaultBlockState());
        });
        helper.runAfterDelay(755, () -> {
            require(helper, engine.isRedstonePowered, "engine did not reactivate after redstone returned");
            require(helper, count(source, original) < afterPowerOff[0],
                "extraction never resumed after the engine was re-powered");
            helper.setBlock(REDSTONE_POS, Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(990, () -> {
            int remaining = count(source, original);
            int delivered = count(destination, original);
            require(helper, remaining + delivered == 20,
                "engine/wood/transport network changed item amount or arbitrary components (source="
                    + remaining + ", dest=" + delivered + ")");
            require(helper, !((PipeFlowItems) extractor.getPipe().getFlow()).doesContainItems()
                && !((PipeFlowItems) line.getPipe().getFlow()).doesContainItems(),
                "an item remained in the pipe after redstone shutdown and drain window");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1110)
    public static void poweredDiamondNetworkSwitchesFromFullToAvailableDestination(GameTestHelper helper) {
        ChestBlockEntity source = chest(helper, SOURCE_POS);
        ItemStack cargo = new ItemStack(Items.APPLE, 14);
        mark(cargo, "engine_sorted", 12_340L);
        source.setItem(0, cargo.copy());
        source.setChanged();
        TilePipeHolder extractor = PipeGameTestSupport.placePipe(helper, WOOD_POS, BCTransportPipes.woodItem);
        PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleItem);
        TilePipeHolder diamond = PipeGameTestSupport.placePipe(helper, new BlockPos(4, 1, 3), BCTransportPipes.diamondItem);
        ChestBlockEntity preferred = chest(helper, new BlockPos(5, 1, 3));
        ChestBlockEntity fallback = chest(helper, new BlockPos(4, 1, 4));
        for (int i = 0; i < preferred.getContainerSize(); i++)
            preferred.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        preferred.setChanged();
        PipeBehaviourDiamondItem sorting = (PipeBehaviourDiamondItem) diamond.getPipe().getBehaviour();
        sorting.filters.setStackInSlot(Direction.EAST.ordinal() * PipeBehaviourDiamond.FILTERS_PER_SIDE,
            new ItemStack(Items.APPLE));
        TileEngineRedstone_BC8 engine = powerEngine(helper, extractor);
        final int[] firstPhase = { 0 };

        helper.runAfterDelay(340, () -> {
            int escaped = count(fallback, cargo);
            require(helper, escaped > 0, "full preferred output prevented powered fallback routing");
            require(helper, count(preferred, cargo) == 0,
                "sorter inserted into a full preferred inventory");
            firstPhase[0] = escaped;
            preferred.setItem(0, ItemStack.EMPTY);
            preferred.setChanged();
        });
        helper.runAfterDelay(815, () -> {
            require(helper, count(preferred, cargo) > 0,
                "preferred route did not recover when space became available");
            require(helper, count(fallback, cargo) >= firstPhase[0],
                "changing preferred route destroyed an already delivered fallback stack");
            helper.setBlock(REDSTONE_POS, Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(1045, () -> {
            require(helper, !engine.isRedstonePowered, "engine still powered after redstone removal");
            int total = count(source, cargo) + count(preferred, cargo) + count(fallback, cargo);
            require(helper, total == 14,
                "power/route-change network lost or duplicated tagged cargo: " + total);
            helper.succeed();
        });
    }

    private static TileEngineRedstone_BC8 powerEngine(GameTestHelper helper, TilePipeHolder extractor) {
        helper.setBlock(ENGINE_POS, BCCoreBlocks.ENGINE_BC8.get().defaultBlockState()
            .setValue(BuildCraftProperties.ENGINE_TYPE, EnumEngineType.WOOD));
        BlockEntity entity = GameTestCompat.getBlockEntity(helper, ENGINE_POS);
        if (!(entity instanceof TileEngineRedstone_BC8 engine)) {
            helper.fail("real redstone engine tile was not created");
            throw new IllegalStateException("missing redstone engine");
        }
        // Real pipe connections are recalculated during server ticks. Do not rotate a
        // directional pipe before the adjacent chest is registered as a TILE connection.
        helper.runAfterDelay(12, () -> {
            PipeBehaviourDirectional wood = (PipeBehaviourDirectional) extractor.getPipe().getBehaviour();
            for (int i = 0; i < 6 && wood.getCurrentDir() != Direction.WEST; i++) wood.advanceFacing();
            require(helper, wood.getCurrentDir() == Direction.WEST,
                "wooden item pipe could not face the actual source inventory");
            engine.preferDirectionOnPlacement(Direction.UP);
        });
        helper.setBlock(REDSTONE_POS, Blocks.REDSTONE_BLOCK.defaultBlockState());
        return engine;
    }

    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.CHEST.defaultBlockState());
        BlockEntity entity = GameTestCompat.getBlockEntity(helper, pos);
        if (!(entity instanceof ChestBlockEntity chest)) {
            helper.fail("missing chest at " + pos);
            throw new IllegalStateException("missing chest");
        }
        return chest;
    }

    private static int count(Container container, ItemStack sample) {
        int amount = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && matches(stack, sample)) amount += stack.getCount();
        }
        return amount;
    }

    private static boolean matches(ItemStack stack, ItemStack original) {
        return ItemStack.isSameItemSameTags(stack, original);
    }

    private static void mark(ItemStack stack, String id, long energy) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString("bcce_scenario", id);
        tag.putLong("external_energy", energy);
    }

    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }
}
