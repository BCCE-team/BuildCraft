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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * In-world Builder lifecycle: a server-owned Template is loaded from the actual world
 * snapshot store, MJ and blocks are consumed by the normal server tick and the
 * machine must react to material starvation or a snapshot replacement mid-build.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemBuilderLifecycleGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final UUID AUTHOR = UUID.fromString("c70a1991-8fe7-4126-84ec-86016ca9f3e9");

    private SystemBuilderLifecycleGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 510)
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

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 570)
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

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 850)
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
            buildcraft.transport.pipe.behaviour.PipeBehaviourWood wood =
                (buildcraft.transport.pipe.behaviour.PipeBehaviourWood) extractor.getPipe().getBehaviour();
            // Both Builder and chest expose inventories. Aim the wooden pipe at
            // the warehouse explicitly, as a player would with a wrench.
            for (int attempt = 0; attempt < 6 && wood.getCurrentDir() != Direction.SOUTH; attempt++) {
                wood.advanceFacing();
            }
            require(helper, wood.getCurrentDir() == Direction.SOUTH,
                "Wooden Pipe could not face the real material warehouse");
            long offered = buildcraft.transport.BCTransportConfig.mjPerItem * 2L;
            require(helper, wood.receivePower(offered, FluidAction.EXECUTE) < offered,
                "powered Wooden Pipe failed to withdraw the two required Builder blocks");
        });
        helper.runAfterDelay(755, () -> {
            require(helper, blocks(helper, placement, Blocks.COBBLESTONE) == 2,
                "Builder did not finish real Template placement after pipe material delivery");
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
