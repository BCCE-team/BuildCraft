package buildcraft.gametest;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileAutoWorkbenchItems;
import buildcraft.lib.BCLib;
import buildcraft.transport.BCTransportConfig;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWood;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real recipe selection, crafting, MJ-controlled extraction and two independent warehouses. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemParallelProductionGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;

    private SystemParallelProductionGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 770)
    public static void parallelAutocraftersPreserveRecipeOutputAndIndependentPowerControl(GameTestHelper helper) {
        Production oak = new Production(helper, 1, Items.OAK_LOG, Items.OAK_PLANKS);
        Production spruce = new Production(helper, 5, Items.SPRUCE_LOG, Items.SPRUCE_PLANKS);
        helper.runAfterDelay(260, () -> {
            oak.requireReady(helper);
            spruce.requireReady(helper);
            oak.extract(helper);
            require(helper, spruce.bench.invResult.getStackInSlot(0).getCount() == 4
                && PipeGameTestSupport.countItem(spruce.output, spruce.product) == 0,
                "unpowered independent branch moved its output without MJ");
        });
        helper.runAfterDelay(480, () -> {
            require(helper, PipeGameTestSupport.countItem(oak.output, oak.product) == 4,
                "powered oak line did not deliver its real craft output");
            require(helper, PipeGameTestSupport.countItem(spruce.output, spruce.product) == 0,
                "idle spruce line leaked output without extraction power");
            spruce.extract(helper);
        });
        helper.runAfterDelay(710, () -> {
            require(helper, PipeGameTestSupport.countItem(oak.output, oak.product) == 4
                && PipeGameTestSupport.countItem(spruce.output, spruce.product) == 4,
                "two craft/power/transport lines did not produce four items each");
            require(helper, PipeGameTestSupport.countItem(oak.output, spruce.product) == 0
                && PipeGameTestSupport.countItem(spruce.output, oak.product) == 0,
                "parallel craft outputs crossed warehouses or changed recipe identity");
            require(helper, oak.bench.invMaterials.getStackInSlot(0).isEmpty()
                && spruce.bench.invMaterials.getStackInSlot(0).isEmpty(),
                "parallel recipe execution did not debit one log from each source");
            require(helper, oak.bench.invResult.getStackInSlot(0).isEmpty()
                && spruce.bench.invResult.getStackInSlot(0).isEmpty(),
                "one workshop retained output after dispatch");
            require(helper, !oak.inFlight() && !spruce.inFlight(),
                "completed physical production network left ghost travelling items");
            helper.succeed();
        });
    }

    private static final class Production {
        final Item product;
        final TileAutoWorkbenchItems bench;
        final TilePipeHolder wood;
        final TilePipeHolder transit;
        final ChestBlockEntity output;

        Production(GameTestHelper helper, int z, Item ingredient, Item outputItem) {
            product = outputItem;
            BlockPos source = new BlockPos(1, 1, z);
            helper.setBlock(source, BCFactoryBlocks.AUTO_BENCH_BLOCK.get().defaultBlockState());
            bench = tile(helper, source, TileAutoWorkbenchItems.class);
            wood = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, z), BCTransportPipes.woodItem);
            transit = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, z), BCTransportPipes.cobbleItem);
            BlockPos sink = new BlockPos(4, 1, z);
            helper.setBlock(sink, Blocks.CHEST.defaultBlockState());
            output = tile(helper, sink, ChestBlockEntity.class);
            bench.invBlueprint.setStackInSlot(0, new ItemStack(ingredient));
            bench.invMaterialFilter.setStackInSlot(0, new ItemStack(ingredient));
            bench.invMaterials.setStackInSlot(0, new ItemStack(ingredient));
        }

        void requireReady(GameTestHelper helper) {
            ItemStack result = bench.invResult.getStackInSlot(0);
            require(helper, result.is(product) && result.getCount() == 4,
                "real Auto Workbench did not craft the expected four planks");
            require(helper, wood.getPipe().isConnected(Direction.WEST)
                && wood.getPipe().isConnected(Direction.EAST)
                && transit.getPipe().isConnected(Direction.EAST),
                "production branch is not connected to its source or output chest");
        }

        void extract(GameTestHelper helper) {
            PipeBehaviourWood extractor = (PipeBehaviourWood) wood.getPipe().getBehaviour();
            require(helper, extractor.getCurrentDir() == Direction.WEST,
                "wooden output extractor aimed away from the Auto Workbench");
            long offer = BCTransportConfig.mjPerItem * 4L;
            require(helper, extractor.receivePower(offer, FluidAction.EXECUTE) < offer,
                "real wooden pipe rejected the crafted stock despite supplied MJ");
        }

        boolean inFlight() {
            return ((PipeFlowItems) wood.getPipe().getFlow()).doesContainItems()
                || ((PipeFlowItems) transit.getPipe().getFlow()).doesContainItems();
        }
    }

    private static <T> T tile(GameTestHelper helper, BlockPos pos, Class<T> type) {
        Object entity = GameTestCompat.getBlockEntity(helper, pos);
        if (!type.isInstance(entity)) {
            helper.fail("missing " + type.getSimpleName() + " at " + pos);
            throw new IllegalStateException("missing test machine or inventory");
        }
        return type.cast(entity);
    }

    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }
}
