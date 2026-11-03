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

/** World-ticking Auto Workbench recipe changes across a live output pipe. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemRecipeMutationGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private SystemRecipeMutationGameTests() {}

    /** A recipe switch cannot rewrite a result already travelling through two real pipes. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1050)
    public static void changingRecipeDuringDeliveryKeepsBothCommittedOutputs(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.configure(Items.OAK_LOG);
        helper.runAfterDelay(260, () -> {
            rig.expectResult(helper, Items.OAK_PLANKS, 4);
            rig.extract(helper, 4);
        });
        helper.runAfterDelay(290, () -> {
            rig.configure(Items.SPRUCE_LOG);
            require(helper, rig.bench.invResult.getStackInSlot(0).isEmpty(),
                "recipe switch resurrected the extracted oak result");
        });
        helper.runAfterDelay(485, () -> {
            require(helper, PipeGameTestSupport.countItem(rig.warehouse, Items.OAK_PLANKS) == 4,
                "first committed craft changed identity during an in-flight recipe switch");
            require(helper, PipeGameTestSupport.countItem(rig.warehouse, Items.SPRUCE_PLANKS) == 0,
                "new recipe product appeared before a complete second craft");
        });
        helper.runAfterDelay(580, () -> {
            rig.expectResult(helper, Items.SPRUCE_PLANKS, 4);
            rig.extract(helper, 4);
        });
        helper.runAfterDelay(930, () -> {
            rig.expectWarehouse(helper, 4, 4);
            require(helper, rig.bench.invMaterials.getStackInSlot(0).isEmpty()
                && rig.bench.invResult.getStackInSlot(0).isEmpty(),
                "crafting or extraction left a duplicate result or ingredient");
            require(helper, !rig.inFlight(), "recipe switch left an orphaned pipe payload");
            helper.succeed();
        });
    }

    /** A changed blueprint must not consume the next ingredient while the previous result blocks output. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 1120)
    public static void blockedOldResultDelaysNewRecipeWithoutConsumingItsIngredients(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.configure(Items.OAK_LOG);
        helper.runAfterDelay(260, () -> {
            rig.expectResult(helper, Items.OAK_PLANKS, 4);
            rig.configure(Items.SPRUCE_LOG);
        });
        helper.runAfterDelay(530, () -> {
            rig.expectResult(helper, Items.OAK_PLANKS, 4);
            require(helper, rig.bench.invMaterials.getStackInSlot(0).is(Items.SPRUCE_LOG)
                && rig.bench.invMaterials.getStackInSlot(0).getCount() == 1,
                "blocked output silently consumed the new recipe's only input");
            rig.extract(helper, 4);
        });
        helper.runAfterDelay(790, () -> {
            rig.expectResult(helper, Items.SPRUCE_PLANKS, 4);
            require(helper, rig.bench.invMaterials.getStackInSlot(0).isEmpty(),
                "unblocked workbench did not debit the new recipe's ingredient");
            rig.extract(helper, 4);
        });
        helper.runAfterDelay(1050, () -> {
            rig.expectWarehouse(helper, 4, 4);
            require(helper, rig.bench.invResult.getStackInSlot(0).isEmpty() && !rig.inFlight(),
                "recovery from backpressure left duplicated craft outputs");
            helper.succeed();
        });
    }

    /** An invalid phantom recipe must be inert and must recover when the blueprint becomes craftable. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 850)
    public static void invalidBlueprintDoesNotDebitMaterialsAndValidReplacementCompletesNormally(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.bench.invBlueprint.setStackInSlot(0, new ItemStack(Items.BEDROCK));
        rig.bench.invMaterialFilter.setStackInSlot(0, new ItemStack(Items.OAK_LOG));
        rig.bench.invMaterials.setStackInSlot(0, new ItemStack(Items.OAK_LOG));
        helper.runAfterDelay(275, () -> {
            require(helper, rig.bench.invResult.getStackInSlot(0).isEmpty(),
                "unsupported phantom recipe manufactured output");
            require(helper, rig.bench.invMaterials.getStackInSlot(0).is(Items.OAK_LOG),
                "unsupported phantom recipe debited unrelated real materials");
            rig.bench.invBlueprint.setStackInSlot(0, new ItemStack(Items.OAK_LOG));
            rig.bench.invMaterialFilter.setStackInSlot(0, new ItemStack(Items.OAK_LOG));
        });
        helper.runAfterDelay(550, () -> {
            rig.expectResult(helper, Items.OAK_PLANKS, 4);
            rig.extract(helper, 4);
        });
        helper.runAfterDelay(805, () -> {
            require(helper, PipeGameTestSupport.countItem(rig.warehouse, Items.OAK_PLANKS) == 4,
                "changing a bad recipe into a valid one failed to resume physical production");
            require(helper, rig.bench.invMaterials.getStackInSlot(0).isEmpty()
                && rig.bench.invResult.getStackInSlot(0).isEmpty() && !rig.inFlight(),
                "invalid recipe recovery lost ingredients or duplicated results");
            helper.succeed();
        });
    }

    private static final class Rig {
        final TileAutoWorkbenchItems bench;
        final TilePipeHolder wood;
        final TilePipeHolder middle;
        final ChestBlockEntity warehouse;

        Rig(GameTestHelper helper) {
            BlockPos source = new BlockPos(1, 1, 3);
            helper.setBlock(source, BCFactoryBlocks.AUTO_BENCH_BLOCK.get().defaultBlockState());
            bench = tile(helper, source, TileAutoWorkbenchItems.class);
            wood = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.woodItem);
            middle = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleItem);
            BlockPos output = new BlockPos(4, 1, 3);
            helper.setBlock(output, Blocks.CHEST.defaultBlockState());
            warehouse = tile(helper, output, ChestBlockEntity.class);
        }

        void configure(Item ingredient) {
            bench.invBlueprint.setStackInSlot(0, new ItemStack(ingredient));
            bench.invMaterialFilter.setStackInSlot(0, new ItemStack(ingredient));
            bench.invMaterials.setStackInSlot(0, new ItemStack(ingredient));
        }

        void expectResult(GameTestHelper helper, Item product, int amount) {
            ItemStack stack = bench.invResult.getStackInSlot(0);
            require(helper, stack.is(product) && stack.getCount() == amount,
                "wrong or absent Auto Workbench result: " + stack + " instead of " + product);
        }

        void extract(GameTestHelper helper, int quantity) {
            require(helper, wood.getPipe().isConnected(Direction.WEST)
                && wood.getPipe().isConnected(Direction.EAST)
                && middle.getPipe().isConnected(Direction.EAST),
                "live recipe factory has a disconnected item route");
            PipeBehaviourWood extractor = (PipeBehaviourWood) wood.getPipe().getBehaviour();
            require(helper, extractor.getCurrentDir() == Direction.WEST,
                "wooden extractor faced away from the actual crafting output");
            long offered = BCTransportConfig.mjPerItem * quantity;
            require(helper, extractor.receivePower(offered, FluidAction.EXECUTE) < offered,
                "powered extraction refused a completed craft result");
        }

        void expectWarehouse(GameTestHelper helper, int oak, int spruce) {
            require(helper, PipeGameTestSupport.countItem(warehouse, Items.OAK_PLANKS) == oak
                && PipeGameTestSupport.countItem(warehouse, Items.SPRUCE_PLANKS) == spruce,
                "factory/pipe/warehouse ledger lost, duplicated or reclassified recipe products");
        }

        boolean inFlight() {
            return ((PipeFlowItems) wood.getPipe().getFlow()).doesContainItems()
                || ((PipeFlowItems) middle.getPipe().getFlow()).doesContainItems();
        }
    }

    private static <T> T tile(GameTestHelper helper, BlockPos pos, Class<T> type) {
        Object entity = GameTestCompat.getBlockEntity(helper, pos);
        if (!type.isInstance(entity)) {
            helper.fail("Missing " + type.getSimpleName() + " at " + pos);
            throw new IllegalStateException("missing recipe test machine");
        }
        return type.cast(entity);
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
