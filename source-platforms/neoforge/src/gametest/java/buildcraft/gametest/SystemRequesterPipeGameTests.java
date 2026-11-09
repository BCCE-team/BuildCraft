package buildcraft.gametest;

import java.util.List;

import buildcraft.lib.BCLib;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.robotics.BCRoboticsBlocks;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotLoad;
import buildcraft.robotics.ai.AIRobotUnload;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.statements.ActionStationAcceptItems;
import buildcraft.robotics.statements.ActionStationProvideItems;
import buildcraft.robotics.tile.TileRequester;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.internal.IInjectable;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.component.DataComponents;
import buildcraft.lib.misc.ItemStackUtil;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Cross-module request fulfillment through a real Requester block entity, real robot
 * inventory/loading/unloading rules and a ticking item pipe network. The station
 * scheduler/navigation is a deterministic fixture: these are NOT flight AI tests.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemRequesterPipeGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos SOURCE = new BlockPos(1, 1, 3);
    private static final BlockPos PIPE_IN = new BlockPos(2, 1, 3);
    private static final BlockPos PIPE_OUT = new BlockPos(3, 1, 3);
    private static final BlockPos REQUESTER = new BlockPos(4, 1, 3);

    private SystemRequesterPipeGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 740)
    public static void requesterRobotPipelineCompletesStaggeredTaggedOrder(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        ItemStack original = marked(new ItemStack(Items.APPLE, 20), "staggered", 7_003_001L);
        rig.requester.setRequest(0, original.copy());
        ItemStack first = original.copy();
        first.setCount(12);
        rig.source.setItem(0, first);
        rig.source.setChanged();
        EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        ChestStation pickup = new ChestStation(helper, rig.source);
        PipeStation delivery = new PipeStation(helper, rig.first);

        helper.runAfterDelay(8, () -> {
            rig.assertConnected(helper);
            require(helper, AIRobotLoad.load(robot, pickup, stack -> same(stack, original), 12, false),
                "SIMULATE did not identify the available annotated cargo");
            require(helper, count(rig.source, original) == 12 && count(robot, original) == 0,
                "robot loading simulation changed actual inventories");
            require(helper, AIRobotLoad.load(robot, pickup, stack -> same(stack, original), 12, true),
                "carrier did not withdraw the first shipment");
            require(helper, AIRobotUnload.unload(robot, delivery, true, true),
                "carrier could not inject the first shipment into its connected pipes");
        });
        helper.runAfterDelay(310, () -> {
            ItemStack accepted = rig.requester.inv.getStackInSlot(0);
            require(helper, same(accepted, original) && accepted.getCount() == 12,
                "requester did not retain all components of the first 12 items");
            require(helper, !rig.requester.isFulfilled(0) && rig.requester.requests().size() == 1,
                "requester closed the order before all 20 items arrived");
            require(helper, rig.requester.requests().iterator().next().minimum() == 8,
                "requester advertised an incorrect remaining demand after partial fulfillment");
            require(helper, count(robot, original) == 0 && count(rig.source, original) == 0,
                "first delivery left duplicated cargo in source or robot");
            ItemStack last = original.copy();
            last.setCount(8);
            rig.source.setItem(0, last);
            rig.source.setChanged();
            require(helper, AIRobotLoad.load(robot, pickup, stack -> same(stack, original), 8, true),
                "carrier failed to reload its newly replenished source");
            require(helper, AIRobotUnload.unload(robot, delivery, true, true),
                "second carrier shipment could not enter the pipe network");
        });
        helper.runAfterDelay(665, () -> {
            ItemStack delivered = rig.requester.inv.getStackInSlot(0);
            require(helper, same(delivered, original) && delivered.getCount() == 20,
                "two real robot/pipe shipments changed the request count or NBT/components");
            require(helper, rig.requester.isFulfilled(0) && rig.requester.requests().isEmpty(),
                "request ledger was not resolved after complete physical delivery");
            require(helper, count(robot, original) == 0 && count(rig.source, original) == 0,
                "fulfilled request still has cargo outside the receiver");
            require(helper, !rig.inFlight(), "completed order retained ghost travelling items");
            require(helper, countDrops(helper, original) == 0,
                "fulfilled requester order ejected correctly tagged cargo into world");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 650)
    public static void requesterSeparateOrdersDoNotMergeDistinctRobotParcels(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        ItemStack apples = marked(new ItemStack(Items.APPLE, 6), "separate-apples", 100L);
        ItemStack carrots = marked(new ItemStack(Items.APPLE, 4), "separate-variant-b", 999L);
        rig.requester.setRequest(0, apples.copy());
        rig.requester.setRequest(1, carrots.copy());
        rig.source.setItem(0, apples.copy());
        rig.source.setItem(1, carrots.copy());
        rig.source.setChanged();
        EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        ChestStation pickup = new ChestStation(helper, rig.source);
        PipeStation delivery = new PipeStation(helper, rig.first);

        helper.runAfterDelay(8, () -> {
            rig.assertConnected(helper);
            require(helper, AIRobotLoad.load(robot, pickup, stack -> true, 10, true),
                "robot failed to collect both types of requested cargo");
            require(helper, count(robot, apples) == 6 && count(robot, carrots) == 4,
                "robot merged independent tagged stock or failed to load both requests");
            require(helper, AIRobotUnload.unload(robot, delivery, true, true),
                "robot failed to inject the first parcel into the network");
        });
        helper.runAfterDelay(275, () -> {
            require(helper, rig.requester.inv.getStackInSlot(0).getCount() == 6
                && same(rig.requester.inv.getStackInSlot(0), apples),
                "first requester slot did not receive intact tagged stock");
            require(helper, !rig.requester.isFulfilled(1) && rig.requester.requests().size() == 1,
                "second independent request was prematurely fulfilled");
            require(helper, AIRobotUnload.unload(robot, delivery, true, true),
                "robot did not resume delivery of its remaining second parcel");
        });
        helper.runAfterDelay(585, () -> {
            require(helper, rig.requester.isFulfilled(0) && rig.requester.isFulfilled(1),
                "two separate requester orders were not both fulfilled");
            ItemStack a = rig.requester.inv.getStackInSlot(0);
            ItemStack b = rig.requester.inv.getStackInSlot(1);
            require(helper, a.getCount() == 6 && same(a, apples)
                && b.getCount() == 4 && same(b, carrots),
                "Requester mixed request slots, changed components or duplicated quantities");
            require(helper, rig.requester.requests().isEmpty() && !rig.inFlight(),
                "requester has an orphan reservation or travelling item after completion");
            require(helper, count(robot, apples) + count(robot, carrots) == 0
                && count(rig.source, apples) + count(rig.source, carrots) == 0,
                "robot/source retained a ghost parcel after both deliveries");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 730)
    public static void requesterChangedWhileParcelTravelsRejectsStaleOrderWithoutLoss(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        ItemStack old = marked(new ItemStack(Items.APPLE, 8), "obsolete", 901L);
        ItemStack replacement = marked(new ItemStack(Items.CARROT, 8), "replacement", 902L);
        rig.requester.setRequest(0, old.copy());
        rig.source.setItem(0, old.copy());
        rig.source.setChanged();
        EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        ChestStation pickup = new ChestStation(helper, rig.source);
        PipeStation delivery = new PipeStation(helper, rig.first);

        helper.runAfterDelay(8, () -> {
            rig.assertConnected(helper);
            require(helper, AIRobotLoad.load(robot, pickup, stack -> same(stack, old), 8, true),
                "carrier did not withdraw the initially requested cargo");
            require(helper, AIRobotUnload.unload(robot, delivery, true, true),
                "carrier failed to send the obsolete shipment into live pipes");
        });
        helper.runAfterDelay(10, () -> {
            require(helper, rig.inFlight(), "obsolete shipment was not in flight when request changed");
            rig.requester.setRequest(0, replacement.copy());
        });
        helper.runAfterDelay(300, () -> {
            require(helper, rig.requester.inv.getStackInSlot(0).isEmpty()
                && !rig.requester.isFulfilled(0),
                "requester accepted an obsolete item after a live order change");
            require(helper, rig.requester.requests().size() == 1,
                "requester erased the new replacement order");
            int inSource = count(rig.source, old);
            int inRobot = count(robot, old);
            int dropped = countDrops(helper, old);
            int accounted = inSource + inRobot + dropped;
            require(helper, accounted == 8 && !rig.inFlight(),
                "stale in-flight shipment was lost or duplicated while rejected: " + accounted
                    + "; source=" + inSource + ", robot=" + inRobot + ", dropped=" + dropped
                    + ", inFlight=" + rig.inFlight());
            rig.source.setItem(1, replacement.copy());
            rig.source.setChanged();
            require(helper, AIRobotLoad.load(robot, pickup, stack -> same(stack, replacement), 8, true),
                "robot could not collect stock for the changed order");
            require(helper, AIRobotUnload.unload(robot, delivery, true, true),
                "robot could not dispatch the replacement shipment");
        });
        helper.runAfterDelay(685, () -> {
            ItemStack delivered = rig.requester.inv.getStackInSlot(0);
            require(helper, delivered.getCount() == 8 && same(delivered, replacement),
                "changed order did not receive correct cargo with full components");
            require(helper, rig.requester.isFulfilled(0) && rig.requester.requests().isEmpty(),
                "new order was not closed after physical delivery");
            require(helper, count(rig.source, old) + count(robot, old) + countDrops(helper, old) == 8,
                "cancelling previous demand retroactively changed the rejected cargo ledger");
            require(helper, count(robot, replacement) == 0 && !rig.inFlight(),
                "newly completed request left in-flight or robot leftovers");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 780)
    public static void requesterRejectsSameItemWithWrongCustomChargeThenAcceptsMatchingVariant(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        ItemStack requested = marked(new ItemStack(Items.APPLE, 6), "requested-charge", 100_001L);
        ItemStack incompatible = marked(new ItemStack(Items.APPLE, 6), "wrong-charge", 999_999L);
        rig.requester.setRequest(0, requested.copy());
        rig.source.setItem(0, incompatible.copy());
        rig.source.setChanged();
        EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        ChestStation pickup = new ChestStation(helper, rig.source);
        PipeStation delivery = new PipeStation(helper, rig.first);

        helper.runAfterDelay(10, () -> {
            rig.assertConnected(helper);
            require(helper, AIRobotLoad.load(robot, pickup, stack -> same(stack, incompatible), 6, true),
                "robot could not collect identical-item stock with a distinct charge component");
            require(helper, AIRobotUnload.unload(robot, delivery, true, true),
                "robot could not send charge-incompatible stock through real pipes");
        });
        helper.runAfterDelay(330, () -> {
            require(helper, rig.requester.inv.getStackInSlot(0).isEmpty() && !rig.requester.isFulfilled(0),
                "Requester incorrectly fulfilled a component-exact order with another charge variant");
            require(helper, rig.requester.requests().size() == 1
                && rig.requester.requests().iterator().next().minimum() == 6,
                "Requester changed its demand after receiving an incompatible ItemStack");
            require(helper, count(rig.source, incompatible) + count(robot, incompatible)
                + countDrops(helper, incompatible) == 6 && !rig.inFlight(),
                "rejected charge-bearing items were lost, duplicated or stranded in pipes");
            rig.source.setItem(1, requested.copy());
            rig.source.setChanged();
            require(helper, AIRobotLoad.load(robot, pickup, stack -> same(stack, requested), 6, true),
                "robot could not load the correctly charged item variant");
            require(helper, AIRobotUnload.unload(robot, delivery, true, true),
                "robot could not send correctly charged cargo through pipes");
        });
        helper.runAfterDelay(715, () -> {
            require(helper, rig.requester.isFulfilled(0) && rig.requester.requests().isEmpty(),
                "Requester did not complete the original component-exact order");
            require(helper, rig.requester.inv.getStackInSlot(0).getCount() == 6
                && same(rig.requester.inv.getStackInSlot(0), requested),
                "Requester accepted a substituted charge or lost custom NBT/components");
            require(helper, count(rig.source, incompatible) + count(robot, incompatible)
                + countDrops(helper, incompatible) == 6,
                "successful second delivery changed the ledger of the rejected variant");
            require(helper, count(rig.source, requested) + count(robot, requested) == 0
                && !rig.inFlight(),
                "fulfilled charged order retained duplicated payloads in sender or pipes");
            helper.succeed();
        });
    }

    private static final class Rig {
        final ChestBlockEntity source;
        final TilePipeHolder first;
        final TilePipeHolder last;
        final TileRequester requester;

        Rig(GameTestHelper helper) {
            // empty7x3x7 contains only air. Rejected cargo is a real ItemEntity;
            // without a floor it falls out of countDrops()'s 0..2 height range
            // before the delayed conservation assertion, making the GameTest
            // depend on routing/tick timing rather than actual item survival.
            // Confine ONLY the fixture; neither pipe logic nor item counts change.
            for (int x = 0; x <= 6; x++) {
                for (int z = 0; z <= 6; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                    if (x == 0 || x == 6 || z == 0 || z == 6) {
                        helper.setBlock(new BlockPos(x, 1, z), Blocks.GLASS.defaultBlockState());
                        helper.setBlock(new BlockPos(x, 2, z), Blocks.GLASS.defaultBlockState());
                    }
                }
            }
            helper.setBlock(SOURCE, Blocks.CHEST.defaultBlockState());
            BlockEntity src = GameTestCompat.getBlockEntity(helper, SOURCE);
            if (!(src instanceof ChestBlockEntity chest)) throw new IllegalStateException("missing source chest");
            source = chest;
            first = PipeGameTestSupport.placePipe(helper, PIPE_IN, BCTransportPipes.cobbleItem);
            last = PipeGameTestSupport.placePipe(helper, PIPE_OUT, BCTransportPipes.goldItem);
            helper.setBlock(REQUESTER, BCRoboticsBlocks.REQUESTER.get().defaultBlockState());
            BlockEntity sink = GameTestCompat.getBlockEntity(helper, REQUESTER);
            if (!(sink instanceof TileRequester tile)) throw new IllegalStateException("missing real Requester");
            requester = tile;
        }

        void assertConnected(GameTestHelper helper) {
            require(helper, first.getPipe().isConnected(Direction.WEST)
                && first.getPipe().isConnected(Direction.EAST)
                && last.getPipe().isConnected(Direction.WEST)
                && last.getPipe().isConnected(Direction.EAST),
                "robot delivery pipe network is not connected to its chest and Requester");
        }

        boolean inFlight() {
            return ((PipeFlowItems) first.getPipe().getFlow()).doesContainItems()
                || ((PipeFlowItems) last.getPipe().getFlow()).doesContainItems();
        }
    }

    private static final class ChestStation extends DockingStation {
        private final Container source;
        private final List<StatementSlot> actions;
        ChestStation(GameTestHelper helper, Container source) {
            super(new BlockIndex(helper.absolutePos(SOURCE)), Direction.UP);
            setLevel(helper.getLevel());
            this.source = source;
            StatementSlot statement = new StatementSlot();
            statement.statement = new ActionStationProvideItems();
            statement.parameters = new IStatementParameter[0];
            actions = List.of(statement);
        }
        @Override public Iterable<StatementSlot> getActiveActions() { return actions; }
        @Override public Container getItemInput() { return source; }
    }

    private static final class PipeStation extends DockingStation {
        private final IInjectable output;
        private final List<StatementSlot> actions;
        PipeStation(GameTestHelper helper, TilePipeHolder pipe) {
            super(new BlockIndex(pipe.getPipePos()), Direction.WEST);
            setLevel(helper.getLevel());
            StatementSlot statement = new StatementSlot();
            statement.statement = new ActionStationAcceptItems();
            statement.parameters = new IStatementParameter[0];
            actions = List.of(statement);
            output = new IInjectable() {
                @Override public boolean canInjectItems(Direction from) {
                    return from == Direction.WEST
                        && !((PipeFlowItems) pipe.getPipe().getFlow()).doesContainItems();
                }
                @Override public ItemStack injectItem(ItemStack stack, boolean execute, Direction from,
                                                      DyeColor color, double speed) {
                    PipeFlowItems flow = (PipeFlowItems) pipe.getPipe().getFlow();
                    if (from != Direction.WEST || flow.doesContainItems()) return stack;
                    return flow.injectItemFromRobotStation(stack.copy(), execute, from, color, speed);
                }
            };
        }
        @Override public Iterable<StatementSlot> getActiveActions() { return actions; }
        @Override public IInjectable getItemOutput() { return output; }
        @Override public Direction getItemOutputSide() { return Direction.WEST; }
    }

    private static int count(Container container, ItemStack expected) {
        int amount = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && same(stack, expected)) amount += stack.getCount();
        }
        return amount;
    }

    private static int count(EntityRobot robot, ItemStack expected) {
        int amount = 0;
        for (int i = 0; i < robot.getContainerSize(); i++) {
            ItemStack stack = robot.getItem(i);
            if (!stack.isEmpty() && same(stack, expected)) amount += stack.getCount();
        }
        return amount;
    }

    private static int countDrops(GameTestHelper helper, ItemStack expected) {
        BlockPos first = helper.absolutePos(BlockPos.ZERO);
        BlockPos last = helper.absolutePos(new BlockPos(6, 2, 6));
        AABB area = new AABB(first.getX(), first.getY(), first.getZ(),
            last.getX() + 1.0, last.getY() + 1.0, last.getZ() + 1.0);
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
            .map(ItemEntity::getItem)
            .filter(stack -> same(stack, expected))
            .mapToInt(ItemStack::getCount)
            .sum();
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameComponents(a, b);
    }

    private static ItemStack marked(ItemStack stack, String scenario, long energy) {
        CompoundTag ext = new CompoundTag();
        ext.putString("requester_fixture", scenario);
        ext.putLong("stored_energy", energy);
        CompoundTag data = ItemStackUtil.getCustomData(stack);
        data.put("addon_state", ext);
        ItemStackUtil.setCustomData(stack, data);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Requester " + scenario));
        return stack;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
