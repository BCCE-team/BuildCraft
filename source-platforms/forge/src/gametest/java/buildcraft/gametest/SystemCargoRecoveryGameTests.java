package buildcraft.gametest;

import java.util.List;

import buildcraft.lib.BCLib;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotLoad;
import buildcraft.robotics.ai.AIRobotUnload;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.statements.ActionStationAcceptItems;
import buildcraft.robotics.statements.ActionStationProvideItems;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.internal.IInjectable;
import buildcraft.transport.internal.pipe.IFlowItems;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Reload a real in-flight pipe and a cargo-carrying robot, then run their normal transport paths. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemCargoRecoveryGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private static final BlockPos ENTRY = new BlockPos(2, 1, 3);
    private static final BlockPos NEXT = new BlockPos(3, 1, 3);

    private SystemCargoRecoveryGameTests() {}

    /** A Block Entity replacement models the persistence boundary, not a full dedicated-server process restart. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 680)
    public static void inFlightCargoRestoresIntoRealPipeAndReachesWarehouseExactlyOnce(GameTestHelper helper) {
        chest(helper, new BlockPos(1, 1, 3));
        ChestBlockEntity warehouse = chest(helper, new BlockPos(4, 1, 3));
        TilePipeHolder original = PipeGameTestSupport.placePipe(helper, ENTRY, BCTransportPipes.cobbleItem);
        TilePipeHolder next = PipeGameTestSupport.placePipe(helper, NEXT, BCTransportPipes.goldItem);
        TilePipeHolder[] entry = { original };
        ItemStack cargo = tagged(new ItemStack(Items.APPLE, 17), "live-pipe-before-save", 8_765_432L);
        ItemStack second = tagged(new ItemStack(Items.APPLE, 7), "live-pipe-after-save", 456_789L);

        helper.runAfterDelay(8, () -> {
            require(helper, original.getPipe().isConnected(Direction.WEST)
                && original.getPipe().isConnected(Direction.EAST)
                && next.getPipe().isConnected(Direction.EAST), "live transport topology not connected");
            send(helper, original, cargo);
        });
        helper.runAfterDelay(10, () -> {
            require(helper, inFlight(original), "test did not capture a real travelling item before save");
            CompoundTag saved = original.saveWithFullMetadata();
            require(helper, !saved.isEmpty(), "pipe wrote no Block Entity persistence data");
            // Recreate the tile, not the block. Breaking the block would drop cargo,
            // which is NOT what happens during normal server save/unload.
            BlockPos absolute = helper.absolutePos(ENTRY);
            helper.getLevel().removeBlockEntity(absolute);
            TilePipeHolder restored = new TilePipeHolder(absolute, helper.getLevel().getBlockState(absolute));
            helper.getLevel().setBlockEntity(restored);
            restored.load(saved);
            restored.onLoad();
            entry[0] = restored;
            require(helper, restored.getPipe() != Pipe.EMPTY, "saved pipe definition was not restored");
            require(helper, inFlight(restored), "in-flight cargo vanished immediately on pipe reload");
            require(helper, helper.getLevel().getBlockEntity(absolute) == restored,
                "restored pipe is not the Block Entity in the live world");
        });
        helper.runAfterDelay(280, () -> {
            require(helper, count(warehouse, cargo) == 17,
                "restored travelling cargo was dropped, duplicated or changed components");
            require(helper, countDrops(helper, cargo) == 0, "restoration emitted cargo as Item Entity");
            require(helper, !inFlight(entry[0]) && !inFlight(next),
                "previous travelling item remained in pipe after delivery");
            send(helper, entry[0], second);
        });
        helper.runAfterDelay(590, () -> {
            require(helper, count(warehouse, cargo) == 17 && count(warehouse, second) == 7,
                "pipe after persistence failed a second distinct transfer or combined custom data");
            require(helper, !inFlight(entry[0]) && !inFlight(next), "orphan travelling cargo after second transfer");
            require(helper, countDrops(helper, cargo) == 0 && countDrops(helper, second) == 0,
                "the restored network ejected cargo instead of storing it");
            helper.succeed();
        });
    }

    /** Real robot inventory save/load followed by normal AIRobotUnload and multi-pipe transport. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 430)
    public static void savedRobotCargoPreservesFullItemStateThroughPipeDelivery(GameTestHelper helper) {
        ChestBlockEntity source = chest(helper, new BlockPos(1, 1, 3));
        ChestBlockEntity warehouse = chest(helper, new BlockPos(4, 1, 3));
        TilePipeHolder first = PipeGameTestSupport.placePipe(helper, ENTRY, BCTransportPipes.cobbleItem);
        TilePipeHolder second = PipeGameTestSupport.placePipe(helper, NEXT, BCTransportPipes.goldItem);
        ItemStack weapon = tagged(new ItemStack(Items.DIAMOND_SWORD), "saved-robot-weapon", 123_456_789L);
        weapon.setDamageValue(117);
        weapon.enchant(Enchantments.UNBREAKING, 2);
        ItemStack expected = weapon.copy();
        source.setItem(0, weapon);
        source.setChanged();
        EntityRobot original = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        RobotInput input = new RobotInput(helper, source);
        RobotOutput output = new RobotOutput(first);

        helper.runAfterDelay(8, () -> {
            require(helper, AIRobotLoad.load(original, input, stack -> same(stack, expected), 1, true),
                "real robot failed to extract item from the source chest");
            require(helper, count(original, expected) == 1 && count(source, expected) == 0,
                "robot loading lost or duplicated the original charged item");
            CompoundTag stored = new CompoundTag();
            original.saveWithoutId(stored);
            require(helper, !stored.isEmpty(), "robot did not serialize its loaded inventory");
            EntityRobot restored = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
            restored.load(stored.copy());
            require(helper, count(restored, expected) == 1,
                "robot saved inventory lost damage, name, charge or other components");
            require(helper, AIRobotUnload.unload(restored, output, true, true),
                "restored robot could not unload into a real item pipe");
            require(helper, count(restored, expected) == 0 && count(source, expected) == 0,
                "robot retained a duplicate of the unloaded payload");
        });
        helper.runAfterDelay(360, () -> {
            require(helper, count(warehouse, expected) == 1,
                "robot-to-warehouse restoration changed item identity, damage or custom charge");
            require(helper, countDrops(helper, expected) == 0, "restored robot cargo was dropped during transfer");
            require(helper, !inFlight(first) && !inFlight(second),
                "restored robot delivery left an orphan item in transit");
            helper.succeed();
        });
    }

    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, Blocks.CHEST.defaultBlockState());
        BlockEntity block = helper.getBlockEntity(relative);
        if (block instanceof ChestBlockEntity chest) return chest;
        helper.fail("expected real chest Block Entity at " + relative);
        throw new IllegalStateException("missing chest");
    }

    private static ItemStack tagged(ItemStack stack, String marker, long charge) {
        stack.setHoverName(Component.literal("BCCE persistent cargo " + marker));
        CompoundTag data = stack.getOrCreateTag();
        data.putString("bcce_persistent_marker", marker);
        CompoundTag addon = new CompoundTag();
        addon.putLong("charge", charge);
        addon.putString("custom_owner", "external-test-mod");
        data.put("external_addon_data", addon);
        return stack;
    }

    private static void send(GameTestHelper helper, TilePipeHolder holder, ItemStack stack) {
        require(helper, holder.getPipe().isConnected(Direction.WEST)
            && holder.getPipe().isConnected(Direction.EAST), "pipe disconnected after recovery");
        ItemStack remainder = ((IFlowItems) holder.getPipe().getFlow()).injectItem(
            stack.copy(), true, Direction.WEST, null, 0.015);
        require(helper, remainder.isEmpty(), "pipe rejected real in-flight insertion");
    }

    private static boolean inFlight(TilePipeHolder pipe) {
        return pipe.getPipe() != Pipe.EMPTY
            && ((PipeFlowItems) pipe.getPipe().getFlow()).doesContainItems();
    }

    private static int count(Container inventory, ItemStack expected) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && same(stack, expected)) total += stack.getCount();
        }
        return total;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameTags(a, b);
    }

    private static int countDrops(GameTestHelper helper, ItemStack expected) {
        BlockPos low = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos high = helper.absolutePos(new BlockPos(6, 2, 6));
        AABB area = new AABB(low.getX(), low.getY(), low.getZ(),
            high.getX() + 1, high.getY() + 1, high.getZ() + 1);
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
            .filter(drop -> same(drop.getItem(), expected)).mapToInt(drop -> drop.getItem().getCount()).sum();
    }

    private static final class RobotInput extends DockingStation {
        private final Container inventory;
        private final List<StatementSlot> actions;
        RobotInput(GameTestHelper helper, Container inventory) {
            super(new BlockIndex(helper.absolutePos(new BlockPos(1, 1, 3))), Direction.UP);
            setLevel(helper.getLevel());
            this.inventory = inventory;
            StatementSlot slot = new StatementSlot();
            slot.statement = new ActionStationProvideItems();
            slot.parameters = new IStatementParameter[0];
            actions = List.of(slot);
        }
        @Override public Iterable<StatementSlot> getActiveActions() { return actions; }
        @Override public Container getItemInput() { return inventory; }
    }

    private static final class RobotOutput extends DockingStation {
        private final IInjectable pipeOutput;
        private final List<StatementSlot> actions;
        RobotOutput(TilePipeHolder holder) {
            super(new BlockIndex(holder.getPipePos()), Direction.WEST);
            setLevel(holder.getPipeWorld());
            StatementSlot slot = new StatementSlot();
            slot.statement = new ActionStationAcceptItems();
            slot.parameters = new IStatementParameter[0];
            actions = List.of(slot);
            pipeOutput = new IInjectable() {
                @Override public boolean canInjectItems(Direction side) {
                    return side == Direction.WEST && !inFlight(holder);
                }
                @Override public ItemStack injectItem(ItemStack stack, boolean execute, Direction side,
                                                      DyeColor colour, double speed) {
                    if (!canInjectItems(side)) return stack.copy();
                    return ((PipeFlowItems) holder.getPipe().getFlow()).injectItemFromRobotStation(
                        stack.copy(), execute, side, colour, speed);
                }
            };
        }
        @Override public Iterable<StatementSlot> getActiveActions() { return actions; }
        @Override public IInjectable getItemOutput() { return pipeOutput; }
        @Override public Direction getItemOutputSide() { return Direction.WEST; }
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
