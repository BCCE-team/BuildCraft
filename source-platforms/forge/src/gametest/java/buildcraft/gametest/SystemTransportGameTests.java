package buildcraft.gametest;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import buildcraft.lib.BCLib;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.internal.pipe.IFlowItems;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDiamond;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDiamondItem;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;

/**
 * In-world multi-stage transport tests. These complement single-method flow/behaviour checks.
 * Every test creates a real Pipe Holder network and observes it across multiple server ticks.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemTransportGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos MAX = new BlockPos(6, 2, 6);
    private SystemTransportGameTests() {}

    /**
     * A full preferred destination must not lose the first batch; after creating space,
     * a subsequent batch must return to that destination without retaining stale backpressure.
     */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 490)
    public static void sortedNetworkRecoversAfterBackpressure(GameTestHelper helper) {
        chest(helper, new BlockPos(2, 1, 3));
        ChestBlockEntity preferred = chest(helper, new BlockPos(4, 1, 3));
        ChestBlockEntity fallback = chest(helper, new BlockPos(3, 1, 4));
        fill(preferred);
        TilePipeHolder sorter = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.diamondItem);
        PipeBehaviourDiamondItem behaviour = (PipeBehaviourDiamondItem) sorter.getPipe().getBehaviour();
        behaviour.filters.setStackInSlot(Direction.EAST.ordinal() * PipeBehaviourDiamond.FILTERS_PER_SIDE,
            new ItemStack(Items.APPLE));
        ItemStack first = cargo(new ItemStack(Items.APPLE, 7), "backpressure-first");
        ItemStack second = cargo(new ItemStack(Items.APPLE, 5), "backpressure-second");

        helper.runAfterDelay(8, () -> {
            require(helper, sorter.getPipe().isConnected(Direction.EAST), "preferred side not connected");
            require(helper, sorter.getPipe().isConnected(Direction.SOUTH), "fallback side not connected");
            inject(helper, sorter, first);
        });
        helper.runAfterDelay(190, () -> {
            require(helper, count(fallback, first) == 7, "first batch did not reach fallback");
            require(helper, markedDrops(helper, first).isEmpty(), "first batch was ejected instead of routed");
            preferred.setItem(0, ItemStack.EMPTY);
            preferred.setChanged();
            inject(helper, sorter, second);
        });
        helper.runAfterDelay(420, () -> {
            require(helper, count(preferred, second) == 5, "unblocked preferred route did not recover");
            require(helper, count(fallback, first) == 7, "fallback cargo was changed by recovery");
            require(helper, count(fallback, second) == 0, "second batch ignored available preferred route");
            require(helper, !((PipeFlowItems) sorter.getPipe().getFlow()).doesContainItems(), "sorter kept an orphan payload");
            require(helper, markedDrops(helper, second).isEmpty(), "second batch dropped during recovery");
            SystemTrace.emit("sorted_network_recovery",
                "preferred", Integer.toString(count(preferred, second)),
                "fallback", Integer.toString(count(fallback, first)),
                "misrouted", Integer.toString(count(fallback, second)),
                "inFlight", Boolean.toString(((PipeFlowItems) sorter.getPipe().getFlow()).doesContainItems()),
                "drops", Integer.toString(markedDrops(helper, second).size()));
            helper.succeed();
        });
    }

    /** Real multi-pipe handoffs must preserve arbitrary serialized cargo state, not just Item ID and count. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 380)
    public static void fullItemDataSurvivesThreeRealPipeHandoffs(GameTestHelper helper) {
        chest(helper, new BlockPos(0, 1, 3));
        TilePipeHolder first = PipeGameTestSupport.placePipe(helper, new BlockPos(1, 1, 3), BCTransportPipes.cobbleItem);
        TilePipeHolder second = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.goldItem);
        TilePipeHolder third = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleItem);
        ChestBlockEntity destination = chest(helper, new BlockPos(4, 1, 3));
        ItemStack original = cargo(new ItemStack(Items.DIAMOND_SWORD), "component-transfer");
        original.setDamageValue(117);
        setName(original, "Serialized test sword");
        // This deliberately models an addon's energy-bearing custom stack component/NBT.
        addEnergyData(original, 8_765_432L);
        ItemStack expected = original.copy();
        helper.runAfterDelay(8, () -> {
            require(helper, first.getPipe().isConnected(Direction.EAST), "entry is disconnected");
            require(helper, second.getPipe().isConnected(Direction.EAST), "second handoff is disconnected");
            require(helper, third.getPipe().isConnected(Direction.EAST), "destination handoff is disconnected");
            inject(helper, first, original);
        });
        helper.runAfterDelay(315, () -> {
            ItemStack delivered = find(destination, expected);
            require(helper, !delivered.isEmpty(), "sword was not delivered");
            require(helper, sameItemAndData(expected, delivered), "transport changed item components, NBT, damage or custom name");
            require(helper, delivered.getCount() == 1, "transport changed the count");
            require(helper, markedDrops(helper, expected).isEmpty(), "tagged sword was unexpectedly dropped");
            require(helper, !((PipeFlowItems) first.getPipe().getFlow()).doesContainItems(), "orphan in first pipe");
            require(helper, !((PipeFlowItems) second.getPipe().getFlow()).doesContainItems(), "orphan in second pipe");
            require(helper, !((PipeFlowItems) third.getPipe().getFlow()).doesContainItems(), "orphan in third pipe");
            SystemTrace.emit("enriched_item_pipeline",
                "count", Integer.toString(delivered.getCount()),
                "damage", Integer.toString(delivered.getDamageValue()),
                "sameData", Boolean.toString(sameItemAndData(expected, delivered)),
                "drops", Integer.toString(markedDrops(helper, expected).size()),
                "inFlight", Boolean.toString(((PipeFlowItems) first.getPipe().getFlow()).doesContainItems()
                    || ((PipeFlowItems) second.getPipe().getFlow()).doesContainItems()
                    || ((PipeFlowItems) third.getPipe().getFlow()).doesContainItems()));
            helper.succeed();
        });
    }

    /** Last pipe is deliberately open: it must emit an entity once, never bounce back to centre. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 310)
    public static void openEndedTransportEjectsOneIntactCargoEntity(GameTestHelper helper) {
        chest(helper, new BlockPos(1, 1, 3));
        TilePipeHolder first = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.goldItem);
        TilePipeHolder last = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleItem);
        ItemStack original = cargo(new ItemStack(Items.APPLE, 13), "open-end-ejection");
        setName(original, "Unmodified dropped cargo");
        helper.runAfterDelay(8, () -> inject(helper, first, original));
        helper.runAfterDelay(235, () -> {
            List<ItemEntity> drops = markedDrops(helper, original);
            require(helper, drops.size() == 1, "open end did not create exactly one cargo entity");
            require(helper, sameItemAndData(original, drops.get(0).getItem()), "ejection lost NBT/components");
            require(helper, drops.get(0).getItem().getCount() == 13, "ejection changed item count");
            require(helper, !((PipeFlowItems) first.getPipe().getFlow()).doesContainItems(), "cargo bounced into the first pipe");
            require(helper, !((PipeFlowItems) last.getPipe().getFlow()).doesContainItems(), "cargo bounced into the last pipe");
            helper.succeed();
        });
    }

    /** Filtered Void destination may destroy only the matching cargo, not the fallback. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 430)
    public static void voidBranchDisposesOnlyFilteredItemAndPreservesOtherCargo(GameTestHelper helper) {
        chest(helper, new BlockPos(0, 1, 3));
        TilePipeHolder entry = PipeGameTestSupport.placePipe(helper, new BlockPos(1, 1, 3), BCTransportPipes.cobbleItem);
        TilePipeHolder sorter = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.diamondItem);
        TilePipeHolder trash = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.voidItem);
        TilePipeHolder fallbackPipe = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 4), BCTransportPipes.cobbleItem);
        ChestBlockEntity output = chest(helper, new BlockPos(2, 1, 5));
        PipeBehaviourDiamondItem behaviour = (PipeBehaviourDiamondItem) sorter.getPipe().getBehaviour();
        behaviour.filters.setStackInSlot(Direction.EAST.ordinal() * PipeBehaviourDiamond.FILTERS_PER_SIDE,
            new ItemStack(Items.APPLE));
        ItemStack intendedTrash = cargo(new ItemStack(Items.APPLE, 4), "void-only-apple");
        ItemStack keep = cargo(new ItemStack(Items.CARROT, 6), "keep-carrot");
        helper.runAfterDelay(8, () -> {
            require(helper, sorter.getPipe().isConnected(Direction.EAST), "void branch disconnected");
            require(helper, sorter.getPipe().isConnected(Direction.SOUTH), "normal branch disconnected");
            inject(helper, entry, intendedTrash);
        });
        helper.runAfterDelay(145, () -> inject(helper, entry, keep));
        helper.runAfterDelay(365, () -> {
            require(helper, count(output, keep) == 6, "non-void cargo was lost or filtered incorrectly");
            require(helper, count(output, intendedTrash) == 0, "void cargo leaked into regular storage");
            require(helper, markedDrops(helper, intendedTrash).isEmpty(), "void cargo was ejected instead of disposed");
            require(helper, markedDrops(helper, keep).isEmpty(), "normal cargo dropped instead of reaching storage");
            require(helper, !((PipeFlowItems) sorter.getPipe().getFlow()).doesContainItems(), "sorter holds stale cargo");
            require(helper, !((PipeFlowItems) trash.getPipe().getFlow()).doesContainItems(), "void pipe holds stale cargo");
            require(helper, !((PipeFlowItems) fallbackPipe.getPipe().getFlow()).doesContainItems(), "normal pipe holds stale cargo");
            helper.succeed();
        });
    }

    /** Break a real pipe with a payload still in it, then rebuild the line for a new payload. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 465)
    public static void brokenPipeDoesNotDuplicateCargoAndCanBeRebuilt(GameTestHelper helper) {
        BlockPos origin = new BlockPos(2, 1, 3);
        // The empty GameTest template has no guaranteed solid floor. Items
        // released by breaking a pipe must remain inside the collection volume
        // for the complete 375-tick regression, rather than falling into the
        // void and being mistaken for lost cargo.
        for (int x = 1; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        chest(helper, new BlockPos(1, 1, 3));
        TilePipeHolder first = PipeGameTestSupport.placePipe(helper, origin, BCTransportPipes.cobbleItem);
        PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleItem);
        ChestBlockEntity output = chest(helper, new BlockPos(4, 1, 3));
        ItemStack interrupted = cargo(new ItemStack(Items.APPLE, 9), "demolition-original");
        ItemStack recovered = cargo(new ItemStack(Items.CARROT, 5), "demolition-retry");
        helper.runAfterDelay(8, () -> inject(helper, first, interrupted));
        helper.runAfterDelay(12, () -> {
            require(helper, ((PipeFlowItems) first.getPipe().getFlow()).doesContainItems(), "fixture cargo left before demolition");
            helper.getLevel().destroyBlock(helper.absolutePos(origin), true);
        });
        helper.runAfterDelay(40, () -> {
            int available = count(output, interrupted) + totalDropped(helper, interrupted);
            require(helper, available == 9, "live demolition duplicated or destroyed cargo: " + available);
            TilePipeHolder rebuilt = PipeGameTestSupport.placePipe(helper, origin, BCTransportPipes.cobbleItem);
            helper.runAfterDelay(54, () -> inject(helper, rebuilt, recovered));
        });
        helper.runAfterDelay(375, () -> {
            require(helper, count(output, recovered) == 5, "rebuilt network did not deliver new cargo");
            require(helper, count(output, interrupted) + totalDropped(helper, interrupted) == 9,
                "old cargo was duplicated/removed after network rebuild");
            require(helper, markedDrops(helper, recovered).isEmpty(), "new cargo leaked during recovery");
            helper.succeed();
        });
    }


    /** Two genuine pipe networks run in parallel, each with different custom component data. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 390)
    public static void independentParallelLinesPreserveAllCargoState(GameTestHelper helper) {
        chest(helper, new BlockPos(1, 1, 1));
        chest(helper, new BlockPos(1, 1, 5));
        TilePipeHolder a0 = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 1), BCTransportPipes.goldItem);
        TilePipeHolder a1 = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 1), BCTransportPipes.cobbleItem);
        TilePipeHolder b0 = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 5), BCTransportPipes.cobbleItem);
        TilePipeHolder b1 = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 5), BCTransportPipes.goldItem);
        ChestBlockEntity targetA = chest(helper, new BlockPos(4, 1, 1));
        ChestBlockEntity targetB = chest(helper, new BlockPos(4, 1, 5));
        ItemStack first = cargo(new ItemStack(Items.IRON_SWORD), "parallel-a");
        first.setDamageValue(37);
        setName(first, "Alpha with energy");
        addEnergyData(first, 347_211L);
        ItemStack second = cargo(new ItemStack(Items.IRON_SWORD), "parallel-b");
        second.setDamageValue(124);
        setName(second, "Beta with energy");
        addEnergyData(second, 948_107L);
        helper.runAfterDelay(8, () -> {
            require(helper, a0.getPipe().isConnected(Direction.WEST), "line A source connection absent");
            require(helper, b0.getPipe().isConnected(Direction.WEST), "line B source connection absent");
            inject(helper, a0, first);
            inject(helper, b0, second);
        });
        helper.runAfterDelay(335, () -> {
            require(helper, count(targetA, first) == 1 && count(targetB, second) == 1,
                "parallel lines did not deliver exactly one original weapon per line");
            require(helper, count(targetA, second) == 0 && count(targetB, first) == 0,
                "parallel lines crossed cargo or changed metadata");
            require(helper, markedDrops(helper, first).isEmpty() && markedDrops(helper, second).isEmpty(),
                "one of the parallel lines ejected its cargo");
            require(helper, !((PipeFlowItems) a0.getPipe().getFlow()).doesContainItems()
                && !((PipeFlowItems) a1.getPipe().getFlow()).doesContainItems()
                && !((PipeFlowItems) b0.getPipe().getFlow()).doesContainItems()
                && !((PipeFlowItems) b1.getPipe().getFlow()).doesContainItems(),
                "orphaned cargo remained after concurrent transfers");
            helper.succeed();
        });
    }

    /** Actual sand-stone pipe is not allowed to couple to an inventory on the wrong side. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 380)
    public static void sandstoneDoesNotLeakCargoIntoAdjacentInventory(GameTestHelper helper) {
        chest(helper, new BlockPos(1, 1, 3));
        TilePipeHolder first = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.cobbleItem);
        TilePipeHolder separator = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.sandstoneItem);
        PipeGameTestSupport.placePipe(helper, new BlockPos(4, 1, 3), BCTransportPipes.cobbleItem);
        ChestBlockEntity target = chest(helper, new BlockPos(5, 1, 3));
        ChestBlockEntity forbidden = chest(helper, new BlockPos(3, 1, 4));
        ItemStack cargo = cargo(new ItemStack(Items.GOLD_INGOT, 9), "sandstone-inventory-isolation");
        helper.runAfterDelay(8, () -> {
            require(helper, first.getPipe().isConnected(Direction.EAST), "entry is not connected to sandstone pipe");
            require(helper, separator.getPipe().isConnected(Direction.WEST)
                && separator.getPipe().isConnected(Direction.EAST), "sandstone pipe disconnected from transport pipes");
            require(helper, !separator.getPipe().isConnected(Direction.SOUTH),
                "sandstone illegally connected to adjacent chest");
            inject(helper, first, cargo);
        });
        helper.runAfterDelay(315, () -> {
            require(helper, count(target, cargo) == 9, "cargo failed to pass through sandstone pipe");
            require(helper, count(forbidden, cargo) == 0, "sandstone inserted items into an adjacent chest");
            require(helper, markedDrops(helper, cargo).isEmpty(), "sandstone cargo leaked into world");
            helper.succeed();
        });
    }

    /** Recolouring a live pipe must invalidate old connections on BOTH pipe holders. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 400)
    public static void colouredLineDisconnectsAndRecoversWithoutLosingData(GameTestHelper helper) {
        chest(helper, new BlockPos(1, 1, 3));
        TilePipeHolder first = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.cobbleItem);
        TilePipeHolder middle = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleItem);
        TilePipeHolder last = PipeGameTestSupport.placePipe(helper, new BlockPos(4, 1, 3), BCTransportPipes.cobbleItem);
        ChestBlockEntity destination = chest(helper, new BlockPos(5, 1, 3));
        first.getPipe().setColour(net.minecraft.world.item.DyeColor.RED);
        middle.getPipe().setColour(net.minecraft.world.item.DyeColor.BLUE);
        last.getPipe().setColour(net.minecraft.world.item.DyeColor.RED);
        ItemStack cargo = cargo(new ItemStack(Items.DIAMOND_PICKAXE), "colour-reconnection");
        cargo.setDamageValue(99);
        addEnergyData(cargo, 61_440L);
        helper.runAfterDelay(12, () -> {
            require(helper, !first.getPipe().isConnected(Direction.EAST), "incompatible colours unexpectedly connected");
            middle.getPipe().setColour(net.minecraft.world.item.DyeColor.RED);
        });
        helper.runAfterDelay(34, () -> {
            require(helper, first.getPipe().isConnected(Direction.EAST), "line did not reconnect on first segment");
            require(helper, middle.getPipe().isConnected(Direction.WEST), "line did not reconnect on middle segment");
            require(helper, middle.getPipe().isConnected(Direction.EAST), "line did not reconnect on last segment");
            require(helper, last.getPipe().isConnected(Direction.WEST), "last segment has stale connection data");
            inject(helper, first, cargo);
        });
        helper.runAfterDelay(310, () -> {
            require(helper, count(destination, cargo) == 1, "reconnected coloured line lost metadata or cargo");
            middle.getPipe().setColour(net.minecraft.world.item.DyeColor.BLUE);
        });
        helper.runAfterDelay(335, () -> {
            require(helper, !first.getPipe().isConnected(Direction.EAST)
                && !middle.getPipe().isConnected(Direction.WEST), "colour change left a one-way stale connection");
            require(helper, count(destination, cargo) == 1, "changing colours after delivery duplicated cargo");
            helper.succeed();
        });
    }

    /** Partial acceptance by a real full chest must conserve the unaccepted travelling payload. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 430)
    public static void nearlyFullDestinationPreservesPartialRemainder(GameTestHelper helper) {
        chest(helper, new BlockPos(1, 1, 3));
        TilePipeHolder first = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.cobbleItem);
        TilePipeHolder last = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.goldItem);
        ChestBlockEntity destination = chest(helper, new BlockPos(4, 1, 3));
        fill(destination);
        ItemStack cargo = cargo(new ItemStack(Items.APPLE, 20), "partial-real-destination");
        ItemStack existing = cargo.copy();
        existing.setCount(60);
        destination.setItem(0, existing);
        destination.setChanged();
        helper.runAfterDelay(8, () -> inject(helper, first, cargo));
        helper.runAfterDelay(350, () -> {
            int delivered = count(destination, cargo) - 60;
            int dropped = totalDropped(helper, cargo);
            require(helper, delivered == 4, "nearly full chest accepted " + delivered + " instead of four items");
            require(helper, delivered + dropped == 20,
                "partial insertion lost or duplicated items: delivered=" + delivered + " dropped=" + dropped);
            require(helper, !((PipeFlowItems) first.getPipe().getFlow()).doesContainItems()
                && !((PipeFlowItems) last.getPipe().getFlow()).doesContainItems(),
                "rejected remainder remains orphaned in transport");
            helper.succeed();
        });
    }

    // Utilities: inspect the actual world, not a mock transport flow.
    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.CHEST.defaultBlockState());
        BlockEntity entity = getBlockEntity(helper, pos);
        if (!(entity instanceof ChestBlockEntity chest)) {
            helper.fail("missing chest at " + pos);
            throw new IllegalStateException("no chest");
        }
        return chest;
    }

    private static void fill(ChestBlockEntity chest) {
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
    }

    private static void inject(GameTestHelper helper, TilePipeHolder pipe, ItemStack stack) {
        IFlowItems flow = (IFlowItems) pipe.getPipe().getFlow();
        ItemStack remaining = flow.injectItem(stack.copy(), true, Direction.WEST, null, 0.05);
        require(helper, remaining.isEmpty(), "pipe rejected input: " + remaining);
    }

    private static int count(Container container, ItemStack original) {
        int found = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && sameItemAndData(stack, original)) found += stack.getCount();
        }
        return found;
    }

    private static ItemStack find(Container container, ItemStack original) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && sameItemAndData(stack, original)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static List<ItemEntity> markedDrops(GameTestHelper helper, ItemStack original) {
        BlockPos min = helper.absolutePos(BlockPos.ZERO);
        BlockPos max = helper.absolutePos(MAX);
        AABB box = new AABB(min.getX(), min.getY(), min.getZ(),
            max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, box).stream()
            .filter(entity -> sameItemAndData(entity.getItem(), original)).toList();
    }

    private static int totalDropped(GameTestHelper helper, ItemStack original) {
        return markedDrops(helper, original).stream().mapToInt(item -> item.getItem().getCount()).sum();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }

    private static BlockEntity getBlockEntity(GameTestHelper helper, BlockPos pos) { return helper.getBlockEntity(pos); }
    private static boolean sameItemAndData(ItemStack a, ItemStack b) { return ItemStack.isSameItemSameTags(a, b); }
    private static void setName(ItemStack stack, String name) { stack.setHoverName(Component.literal(name)); }
    private static ItemStack cargo(ItemStack stack, String marker) {
        stack.getOrCreateTag().putString("bcce_system_cargo", marker);
        return stack;
    }
    private static void addEnergyData(ItemStack stack, long energy) {
        CompoundTag machine = new CompoundTag();
        machine.putLong("energy", energy);
        machine.putString("owner", "external-addon");
        stack.getOrCreateTag().put("external_machine", machine);
    }
}
