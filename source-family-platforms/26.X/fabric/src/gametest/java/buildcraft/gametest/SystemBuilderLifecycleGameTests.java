package buildcraft.gametest;

import java.util.BitSet;
import java.util.Date;
import java.util.UUID;

import buildcraft.api.v2.energy.MjAmount;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Template;
import buildcraft.builders.tile.TileBuilder;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.internal.enums.EnumSnapshotType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler.FluidAction;
/**
 * In-world Builder lifecycle: a server-owned Template is loaded from the actual world
 * snapshot store, MJ and blocks are consumed by the normal server tick and the
 * machine must react to material starvation or a snapshot replacement mid-build.
 */
public final class SystemBuilderLifecycleGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final UUID AUTHOR = UUID.fromString("c70a1991-8fe7-4126-84ec-86016ca9f3e9");

    private SystemBuilderLifecycleGameTests() {}

    public static void worldTemplateBuilderStarvesThenResumesWithoutFreeBlocks(GameTestHelper helper) {
        try {
        TileBuilder builder = builder(helper);
        Template template = template(2);
        install(helper, builder, template);
        builder.invResources.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 1));
        power(builder);
        BlockPos[] targets = targets(helper, builder, 2);

        helper.runAfterDelay(175, () -> {
            require(helper, blocks(helper, targets, Blocks.COBBLESTONE) == 1,
                "builder did not stop after consuming its only cobblestone");
            require(helper, remainingCobblestone(builder) == 0,
                "builder placed a block but failed to debit the actual material inventory");
            builder.invResources.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 1));
        });
        helper.runAfterDelay(440, () -> {
            require(helper, blocks(helper, targets, Blocks.COBBLESTONE) == 2,
                "builder did not resume both world placements after the warehouse was refilled");
            require(helper, remainingCobblestone(builder) == 0,
                "builder completed two placements without consuming exactly two blocks");
            helper.succeed();
        });
        } catch (RuntimeException | AssertionError error) {
            // Synchronous GameTest setup errors are otherwise reported as the
            // unhelpful vanilla "sync failed". Preserve the original failure and stack.
            BCLog.logger.error("Builder GameTest worldTemplateBuilderStarvesThenResumesWithoutFreeBlocks setup failed", error);
            throw error;
        }
    }

    public static void cancellingHalfBuiltTemplateDoesNotPlacePhantomBlocks(GameTestHelper helper) {
        try {
        TileBuilder builder = builder(helper);
        Template template = template(2);
        ItemStack card = install(helper, builder, template);
        builder.invResources.setStackInSlot(0, new ItemStack(Items.COBBLESTONE));
        power(builder);
        BlockPos[] targets = targets(helper, builder, 2);
        helper.runAfterDelay(180, () -> {
            require(helper, blocks(helper, targets, Blocks.COBBLESTONE) == 1,
                "fixture must have exactly one completed placement before cancellation");
            builder.invSnapshot.setStackInSlot(0, ItemStack.EMPTY);
            builder.invResources.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 1));
        });
        helper.runAfterDelay(310, () -> {
            require(helper, blocks(helper, targets, Blocks.COBBLESTONE) == 1,
                "removed template kept building from a stale in-memory task");
            builder.invSnapshot.setStackInSlot(0, card.copy());
        });
        helper.runAfterDelay(515, () -> {
            require(helper, blocks(helper, targets, Blocks.COBBLESTONE) == 2,
                "reinstalling template did not resume from actual world state");
            require(helper, remainingCobblestone(builder) == 0,
                "template resumed with an incorrect material ledger");
            helper.succeed();
        });
        } catch (RuntimeException | AssertionError error) {
            // Synchronous GameTest setup errors are otherwise reported as the
            // unhelpful vanilla "sync failed". Preserve the original failure and stack.
            BCLog.logger.error("Builder GameTest cancellingHalfBuiltTemplateDoesNotPlacePhantomBlocks setup failed", error);
            throw error;
        }
    }

    public static void starvedTemplateBuilderResumesFromRealWoodenPipeMaterialDelivery(GameTestHelper helper) {
        try {
        TileBuilder builder = builder(helper);
        Template template = template(2);
        install(helper, builder, template);
        power(builder);
        BlockPos[] placement = targets(helper, builder, 2);
        BlockPos sourcePos = new BlockPos(1, 1, 5);
        BlockPos pipePos = new BlockPos(1, 1, 4);
        for (BlockPos target : placement) {
            require(helper, !target.equals(sourcePos) && !target.equals(pipePos),
                "Builder Template overlaps the logistics fixture");
        }
        helper.setBlock(sourcePos, Blocks.CHEST.defaultBlockState());
        if (!(GameTestCompat.getBlockEntity(helper, sourcePos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest)) {
            helper.fail("missing physical Builder material warehouse");
            return;
        }
        buildcraft.transport.tile.TilePipeHolder extractor = PipeGameTestSupport.placePipe(
            helper, pipePos, buildcraft.transport.BCTransportPipes.woodItem);
        buildcraft.transport.pipe.behaviour.PipeBehaviourWood wood =
            (buildcraft.transport.pipe.behaviour.PipeBehaviourWood) extractor.getPipe().getBehaviour();

        helper.runAfterDelay(190, () -> {
            require(helper, blocks(helper, placement, Blocks.COBBLESTONE) == 0,
                "Builder placed Template blocks before any warehouse materials arrived");
            require(helper, remainingCobblestone(builder) == 0,
                "Builder minted resources before receiving physical items");
            require(helper, extractor.getPipe().isConnected(Direction.SOUTH)
                && extractor.getPipe().isConnected(Direction.NORTH),
                "Wooden Pipe is not connected from source warehouse to Builder");
            chest.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
            chest.setChanged();
            // Both Builder and chest expose inventories. Aim the wooden pipe at
            // the warehouse explicitly, as a player would with a wrench.
            for (int attempt = 0; attempt < 6 && wood.getCurrentDir() != Direction.SOUTH; attempt++) {
                wood.advanceFacing();
            }
            require(helper, wood.getCurrentDir() == Direction.SOUTH,
                "Wooden Pipe could not face the real material warehouse");
            long offered = buildcraft.transport.BCTransportConfig.mjPerItem * 2L;
            require(helper, wood.receivePower(offered, FluidAction.EXECUTE) < offered,
                "powered Wooden Pipe failed to withdraw Builder materials");
        });
        // Wooden Pipes are allowed to accept only part of an offered item stack per power
        // pulse. This test must actually transfer both blocks, rather than treating the
        // first nonzero extraction as proof that the entire warehouse was emptied.
        helper.runAfterDelay(225, () -> {
            int remaining = chest.getItem(0).getCount();
            if (remaining > 0) {
                require(helper, wood.getCurrentDir() == Direction.SOUTH,
                    "Wooden Pipe lost its warehouse-facing direction before the retry");
                long retryOffer = buildcraft.transport.BCTransportConfig.mjPerItem * (long) remaining;
                require(helper, wood.receivePower(retryOffer, FluidAction.EXECUTE) < retryOffer,
                    "Wooden Pipe failed to extract the warehouse remainder");
            }
            require(helper, chest.getItem(0).isEmpty(),
                "Wooden Pipe failed to withdraw both cobblestone blocks from its warehouse");
        });
        helper.runAfterDelay(755, () -> {
            int placed = blocks(helper, placement, Blocks.COBBLESTONE);
            require(helper, placed == 2,
                "Builder did not finish real Template placement after pipe material delivery"
                    + "; placed=" + placed + ", warehouse=" + chest.getItem(0).getCount()
                    + ", builderInventory=" + remainingCobblestone(builder)
                    + ", pipeInFlight=" + ((buildcraft.transport.pipe.flow.PipeFlowItems)
                        extractor.getPipe().getFlow()).doesContainItems());
            require(helper, remainingCobblestone(builder) == 0 && chest.getItem(0).isEmpty(),
                "completed Builder consumed a different number of items than the warehouse sent");
            require(helper, !((buildcraft.transport.pipe.flow.PipeFlowItems) extractor.getPipe().getFlow()).doesContainItems(),
                "completed construction left ghost cobblestone travelling inside the pipe");
            helper.succeed();
        });
        } catch (RuntimeException | AssertionError error) {
            // Synchronous GameTest setup errors are otherwise reported as the
            // unhelpful vanilla "sync failed". Preserve the original failure and stack.
            BCLog.logger.error("Builder GameTest starvedTemplateBuilderResumesFromRealWoodenPipeMaterialDelivery setup failed", error);
            throw error;
        }
    }

    /** One bedrock cell must not hold the rest of the structure hostage. */

    public static void unbreakableTemplateCellDoesNotStarveIndependentPlacement(GameTestHelper helper) {
        TileBuilder builder = builder(helper);
        Template plan = template(2);
        plan.data.clear(0); // First cell must be empty in the finished structure.
        plan.computeKey();
        install(helper, builder, plan);
        BlockPos[] cells = targets(helper, builder, 2);
        helper.setBlock(cells[0], Blocks.BEDROCK.defaultBlockState());
        builder.invResources.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 1));
        power(builder);
        helper.runAfterDelay(620, () -> {
            require(helper, helper.getLevel().getBlockState(helper.absolutePos(cells[0])).is(Blocks.BEDROCK),
                "Builder destroyed a non-excavatable block");
            require(helper, blocks(helper, cells, Blocks.COBBLESTONE) == 1,
                "bedrock task starved otherwise independent placement");
            require(helper, remainingCobblestone(builder) == 0,
                "independent placement did not consume exactly one item");
            require(helper, !builder.invSnapshot.getStackInSlot(0).isEmpty(),
                "Builder falsely completed a blueprint with unresolved excavation");
            helper.setBlock(cells[0], Blocks.AIR.defaultBlockState());
        });
        helper.runAfterDelay(720, () -> {
            require(helper, builder.invSnapshot.getStackInSlot(0).isEmpty(),
                "Builder did not finish after the obstruction was cleared");
            helper.succeed();
        });
    }

    /** A source block cannot be mined as a normal block; build unaffected cells. */

    public static void liquidObstructionDoesNotStarveIndependentPlacement(GameTestHelper helper) {
        TileBuilder builder = builder(helper);
        Template plan = template(3);
        plan.data.clear(0);
        plan.computeKey();
        install(helper, builder, plan);
        BlockPos[] cells = targets(helper, builder, 3);
        // Cell 1 is already correct and separates the source from the target
        // cell 2. Seal the other faces so water cannot flow into the work area.
        helper.setBlock(cells[1], Blocks.STONE.defaultBlockState());
        for (Direction direction : Direction.values()) {
            BlockPos wall = cells[0].relative(direction);
            if (!wall.equals(cells[1]) && !wall.equals(new BlockPos(1, 1, 3))) {
                helper.setBlock(wall, Blocks.GLASS.defaultBlockState());
            }
        }
        helper.setBlock(cells[0], Blocks.WATER.defaultBlockState());
        builder.invResources.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 1));
        power(builder);
        helper.runAfterDelay(620, () -> {
            require(helper, helper.getLevel().getFluidState(helper.absolutePos(cells[0])).isSource(),
                "Builder unexpectedly removed the water source");
            require(helper, blocks(helper, cells, Blocks.COBBLESTONE) == 1,
                "fluid obstruction starved independent placement");
            require(helper, remainingCobblestone(builder) == 0,
                "fluid-obstruction test lost or duplicated resources");
            require(helper, !builder.invSnapshot.getStackInSlot(0).isEmpty(),
                "Builder falsely completed a plan with a fluid obstruction");
            helper.succeed();
        });
    }

    private static Template template(int length) {
        Template template = new Template();
        template.size = new BlockPos(length, 1, 1);
        template.offset = BlockPos.ZERO;
        template.facing = Direction.WEST;
        template.data = new BitSet(length);
        template.data.set(0, length);
        template.computeKey();
        return template;
    }

    private static TileBuilder builder(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 3);
        helper.setBlock(pos, BCBuildersBlocks.BUILDER.get().defaultBlockState()
            .setValue(BlockBCBase_Neptune.PROP_FACING, Direction.WEST));
        BlockEntity blockEntity = GameTestCompat.getBlockEntity(helper, pos);
        if (!(blockEntity instanceof TileBuilder builder)) {
            helper.fail("builder block has no real TileBuilder");
            throw new IllegalStateException("missing builder");
        }
        // GameTestHelper.setBlock does not invoke placement callbacks. A player-placed
        // Builder initializes its base poses here before it can resolve a Template.
        builder.onPlacedBy(buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer(
            helper.getLevel(), new com.mojang.authlib.GameProfile(AUTHOR, "BCTestBuilder"),
            helper.absolutePos(pos)), ItemStack.EMPTY);
        return builder;
    }

    private static ItemStack install(GameTestHelper helper, TileBuilder builder, Template template) {
        GlobalSavedDataSnapshots.cacheServerSnapshot(helper.getLevel(), template);
        Snapshot.Header header = new Snapshot.Header(template.key, AUTHOR, new Date(0),
            "Builder GameTest", false, false, true);
        ItemStack item = ItemSnapshot.getUsed(EnumSnapshotType.TEMPLATE, header);
        require(helper, !item.isEmpty(), "template item could not be registered");
        builder.invSnapshot.setStackInSlot(0, item.copy());
        require(helper, builder.getTemplateBuildingInfo() != null,
            "builder failed to resolve the Template from world-owned snapshot storage");
        return item;
    }

    private static BlockPos[] targets(GameTestHelper helper, TileBuilder builder, int size) {
        BlockPos[] result = new BlockPos[size];
        for (int i = 0; i < size; i++) {
            BlockPos absolute = builder.getTemplateBuildingInfo().toWorld(new BlockPos(i, 0, 0));
            BlockPos relative = absolute.subtract(helper.absolutePos(BlockPos.ZERO));
            require(helper, relative.getX() >= 0 && relative.getX() < 7
                && relative.getY() >= 0 && relative.getY() < 3
                && relative.getZ() >= 0 && relative.getZ() < 7,
                "template placement escaped test bounds: " + relative);
            result[i] = relative;
            helper.setBlock(relative, Blocks.AIR.defaultBlockState());
        }
        return result;
    }

    private static void power(TileBuilder builder) {
        builder.getBattery().addPowerChecking(1000L * MjAmount.MICRO_MJ_PER_MJ, FluidAction.EXECUTE);
    }

    private static int remainingCobblestone(TileBuilder builder) {
        int amount = 0;
        for (int slot = 0; slot < builder.invResources.getSlots(); slot++) {
            ItemStack stack = builder.invResources.getStackInSlot(slot);
            if (stack.is(Items.COBBLESTONE)) amount += stack.getCount();
        }
        return amount;
    }

    private static int blocks(GameTestHelper helper, BlockPos[] positions, net.minecraft.world.level.block.Block block) {
        int count = 0;
        for (BlockPos pos : positions) {
            if (helper.getLevel().getBlockState(helper.absolutePos(pos)).is(block)) count++;
        }
        return count;
    }

    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }
}
