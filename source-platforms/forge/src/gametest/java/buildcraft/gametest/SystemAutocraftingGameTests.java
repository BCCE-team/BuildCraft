package buildcraft.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import buildcraft.lib.BCLib;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.tile.TileAutoWorkbenchItems;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.BCTransportConfig;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWood;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;

/** Fully powered in-world production output: recipe -> craft -> pipe extraction -> warehouse. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemAutocraftingGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private SystemAutocraftingGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 570)
    public static void workbenchCraftsAndPoweredWoodPipeExportsIntoRealWarehouse(GameTestHelper helper) {
        BlockPos benchPos = new BlockPos(1, 1, 3);
        helper.setBlock(benchPos, BCFactoryBlocks.AUTO_BENCH_BLOCK.get().defaultBlockState());
        if (!(getBlockEntity(helper, benchPos) instanceof TileAutoWorkbenchItems bench)) {
            helper.fail("missing Auto Workbench block entity");
            return;
        }
        TilePipeHolder wood = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.woodItem);
        TilePipeHolder transport = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleItem);
        BlockPos outputPos = new BlockPos(4, 1, 3);
        helper.setBlock(outputPos, Blocks.CHEST.defaultBlockState());
        if (!(getBlockEntity(helper, outputPos) instanceof ChestBlockEntity warehouse)) {
            helper.fail("missing real output warehouse");
            return;
        }
        // Blueprint and material filter are configuration slots, not external inputs.
        bench.invBlueprint.setStackInSlot(0, new ItemStack(Items.OAK_LOG));
        bench.invMaterialFilter.setStackInSlot(0, new ItemStack(Items.OAK_LOG));
        bench.invMaterials.setStackInSlot(0, new ItemStack(Items.OAK_LOG));

        helper.runAfterDelay(12, () -> {
            require(helper, wood.getPipe().isConnected(Direction.WEST)
                && wood.getPipe().isConnected(Direction.EAST), "wood extractor did not connect to Auto Workbench");
            require(helper, transport.getPipe().isConnected(Direction.EAST), "transport was not connected to warehouse");
            require(helper, ((PipeBehaviourWood) wood.getPipe().getBehaviour()).getCurrentDir() == Direction.WEST,
                "wood extractor points away from the crafting output");
        });
        helper.runAfterDelay(260, () -> {
            ItemStack crafted = bench.invResult.getStackInSlot(0);
            require(helper, crafted.is(Items.OAK_PLANKS) && crafted.getCount() == 4,
                "real workbench failed to craft four planks from its oak log within 260 ticks");
            require(helper, bench.invMaterials.getStackInSlot(0).isEmpty(),
                "crafting did not debit the required oak log");
            PipeBehaviourWood behaviour = (PipeBehaviourWood) wood.getPipe().getBehaviour();
            long offered = BCTransportConfig.mjPerItem * 4L;
            long remaining = behaviour.receivePower(offered, FluidAction.EXECUTE);
            require(helper, remaining < offered, "wooden extraction did not accept power for crafted planks");
        });
        helper.runAfterDelay(495, () -> {
            require(helper, PipeGameTestSupport.countItem(warehouse, Items.OAK_PLANKS) == 4,
                "production network failed to deliver exactly four planks to its warehouse");
            require(helper, bench.invResult.getStackInSlot(0).isEmpty(),
                "workbench still exposes result after successful extraction");
            require(helper, !((PipeFlowItems) wood.getPipe().getFlow()).doesContainItems()
                && !((PipeFlowItems) transport.getPipe().getFlow()).doesContainItems(),
                "workbench output has stuck travelling items");
            helper.succeed();
        });
    }

    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }

    private static net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(GameTestHelper helper, BlockPos pos) { return helper.getBlockEntity(pos); }
}
