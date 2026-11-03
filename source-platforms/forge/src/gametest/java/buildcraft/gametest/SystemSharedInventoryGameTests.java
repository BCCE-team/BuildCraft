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
import buildcraft.transport.BCTransportConfig;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.internal.IInjectable;
import buildcraft.transport.pipe.behaviour.PipeBehaviourDirectional;
import buildcraft.transport.pipe.behaviour.PipeBehaviourWood;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Multiple real world automation endpoints contesting mutable inventories. */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemSharedInventoryGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private SystemSharedInventoryGameTests() {}

    /** Two independently powered wooden pipes debit the SAME chest in the same tick. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 850)
    public static void twoWoodenExtractorsCompeteForOneTaggedWarehouseWithoutDuplication(GameTestHelper helper) {
        ChestBlockEntity source = chest(helper, new BlockPos(3, 1, 3));
        ChestBlockEntity west = chest(helper, new BlockPos(0, 1, 3));
        ChestBlockEntity east = chest(helper, new BlockPos(6, 1, 3));
        TilePipeHolder westLine = PipeGameTestSupport.placePipe(helper, new BlockPos(1, 1, 3), BCTransportPipes.cobbleItem);
        TilePipeHolder westWood = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.woodItem);
        TilePipeHolder eastWood = PipeGameTestSupport.placePipe(helper, new BlockPos(4, 1, 3), BCTransportPipes.woodItem);
        TilePipeHolder eastLine = PipeGameTestSupport.placePipe(helper, new BlockPos(5, 1, 3), BCTransportPipes.cobbleItem);
        ItemStack original = tagged(new ItemStack(Items.IRON_INGOT, 40), "competing-wood", 172_099L);
        source.setItem(0, original.copy());
        source.setChanged();

        helper.runAfterDelay(18, () -> {
            face(helper, westWood, Direction.EAST);
            face(helper, eastWood, Direction.WEST);
            require(helper, westWood.getPipe().isConnected(Direction.EAST)
                && westWood.getPipe().isConnected(Direction.WEST)
                && eastWood.getPipe().isConnected(Direction.EAST)
                && eastWood.getPipe().isConnected(Direction.WEST),
                "one of the two extraction routes is physically disconnected");
            pulse(helper, westWood, 10);
            pulse(helper, eastWood, 10);
        });
        helper.runAfterDelay(245, () -> {
            require(helper, total(source, original) + total(west, original) + total(east, original) <= 40,
                "shared inventory duplicated stock during concurrent extraction");
            pulse(helper, eastWood, 10);
            pulse(helper, westWood, 10);
        });
        helper.runAfterDelay(745, () -> {
            require(helper, total(source, original) == 0,
                "two concurrent wooden pipes left unextracted stock after sufficient MJ pulses");
            require(helper, total(west, original) > 0 && total(east, original) > 0,
                "one of the two legitimate extraction routes was silently starved");
            require(helper, total(west, original) + total(east, original) == 40,
                "competing extraction credited more or fewer items than source inventory contained");
            require(helper, !inFlight(westWood) && !inFlight(westLine)
                && !inFlight(eastWood) && !inFlight(eastLine),
                "competing extraction left a phantom traveller after physical deliveries");
            require(helper, dropped(helper, original) == 0,
                "competing extraction dropped tagged stock instead of delivering it");
            helper.succeed();
        });
    }

    /** A hopper, a robot and a powered pipe all operate on one actual chest. */
    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 810)
    public static void hopperRobotAndPoweredPipeShareChestThroughLiveInventoryMutation(GameTestHelper helper) {
        BlockPos sourcePos = new BlockPos(1, 1, 3);
        ChestBlockEntity source = chest(helper, sourcePos);
        BlockPos hopperPos = sourcePos.below();
        helper.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState());
        HopperBlockEntity hopper = tile(helper, hopperPos, HopperBlockEntity.class);
        TilePipeHolder wood = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.woodItem);
        TilePipeHolder transit = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleItem);
        ChestBlockEntity destination = chest(helper, new BlockPos(4, 1, 3));
        ItemStack original = tagged(new ItemStack(Items.APPLE, 40), "four-agents", 98_765_432L);
        source.setItem(0, original.copy());
        source.setChanged();
        EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        ChestInputStation pickup = new ChestInputStation(helper, source, sourcePos);
        PipeOutputStation delivery = new PipeOutputStation(helper, wood);

        helper.runAfterDelay(38, () -> {
            require(helper, total(hopper, original) > 0,
                "actual hopper failed to extract anything from the shared chest");
            // A physical redstone block stops further hopper extraction during robot/pipe operations.
            helper.setBlock(hopperPos.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            require(helper, AIRobotLoad.load(robot, pickup, stack -> same(stack, original), 6, false),
                "robot preview could not observe intact stock after hopper extraction");
            require(helper, AIRobotLoad.load(robot, pickup, stack -> same(stack, original), 6, true),
                "robot failed to load six items from the shared live chest");
            require(helper, total(robot, original) == 6,
                "robot changed source item components while competing with hopper");
            face(helper, wood, Direction.WEST);
            pulse(helper, wood, 9);
        });
        helper.runAfterDelay(125, () -> {
            ItemStack moved = source.removeItem(0, 5);
            require(helper, same(moved, original) && moved.getCount() == 5,
                "manual server-side slot move observed missing or mutated cargo");
            require(helper, source.getItem(4).isEmpty(), "fixture destination slot unexpectedly occupied");
            source.setItem(4, moved);
            source.setChanged();
            require(helper, total(source, original) + total(hopper, original)
                + total(robot, original) + total(destination, original) <= 40,
                "inventory slot mutation duplicated shared stock during an in-flight handoff");
        });
        helper.runAfterDelay(300, () -> {
            require(helper, !inFlight(wood) && !inFlight(transit),
                "original Wooden Pipe extraction has not completed before robot unload");
            require(helper, AIRobotUnload.unload(robot, delivery, true, true),
                "robot could not deliver its actual six-item cargo to the shared pipe network");
        });
        helper.runAfterDelay(740, () -> {
            require(helper, total(robot, original) == 0 && !inFlight(wood) && !inFlight(transit),
                "robot or item pipes retained cargo after completing both extraction cycles");
            require(helper, total(hopper, original) > 0 && total(destination, original) > 6,
                "one physical participant never received intact components");
            require(helper, total(source, original) + total(hopper, original)
                + total(robot, original) + total(destination, original) == 40,
                "hopper + robot + pipe + manual slot mutation lost or duplicated a tagged ItemStack");
            require(helper, dropped(helper, original) == 0,
                "shared-inventory mutation ejected tagged stock into world");
            helper.succeed();
        });
    }

    private static void pulse(GameTestHelper helper, TilePipeHolder pipe, int count) {
        PipeBehaviourWood wood = (PipeBehaviourWood) pipe.getPipe().getBehaviour();
        long supplied = BCTransportConfig.mjPerItem * count;
        long rejected = wood.receivePower(supplied, FluidAction.EXECUTE);
        require(helper, rejected < supplied,
            "powered wooden pipe failed to withdraw its actual shared-inventory quota");
    }

    private static void face(GameTestHelper helper, TilePipeHolder pipe, Direction expected) {
        PipeBehaviourDirectional wood = (PipeBehaviourDirectional) pipe.getPipe().getBehaviour();
        for (int i = 0; i < 6 && wood.getCurrentDir() != expected; i++) wood.advanceFacing();
        require(helper, wood.getCurrentDir() == expected,
            "wooden extraction side does not face the actual shared container: " + wood.getCurrentDir());
    }

    private static boolean inFlight(TilePipeHolder pipe) {
        return ((PipeFlowItems) pipe.getPipe().getFlow()).doesContainItems();
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return !a.isEmpty() && ItemStack.isSameItemSameTags(a, b);
    }

    private static ItemStack tagged(ItemStack source, String key, long storedEnergy) {
        CompoundTag tag = source.getOrCreateTag();
        CompoundTag internal = new CompoundTag();
        internal.putString("case", key);
        internal.putLong("energy", storedEnergy);
        tag.put("addon_payload", internal);
        source.setTag(tag);
        return source;
    }

    private static int total(Container container, ItemStack reference) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack item = container.getItem(slot);
            if (same(item, reference)) count += item.getCount();
        }
        return count;
    }

    private static int total(EntityRobot robot, ItemStack reference) {
        int count = 0;
        for (int slot = 0; slot < robot.getContainerSize(); slot++) {
            ItemStack item = robot.getItem(slot);
            if (same(item, reference)) count += item.getCount();
        }
        return count;
    }

    private static int dropped(GameTestHelper helper, ItemStack original) {
        BlockPos a = helper.absolutePos(BlockPos.ZERO);
        BlockPos b = helper.absolutePos(new BlockPos(6, 2, 6));
        AABB box = new AABB(a.getX(), a.getY(), a.getZ(), b.getX() + 1, b.getY() + 1, b.getZ() + 1);
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, box).stream()
            .map(ItemEntity::getItem)
            .filter(item -> same(item, original))
            .mapToInt(ItemStack::getCount).sum();
    }

    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.CHEST.defaultBlockState());
        return tile(helper, pos, ChestBlockEntity.class);
    }

    private static <T> T tile(GameTestHelper helper, BlockPos pos, Class<T> type) {
        Object entity = helper.getBlockEntity(pos);
        if (!type.isInstance(entity)) {
            helper.fail("missing " + type.getSimpleName() + " at " + pos);
            throw new IllegalStateException("missing shared-inventory fixture");
        }
        return type.cast(entity);
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }

    private static final class ChestInputStation extends DockingStation {
        final Container input;
        final List<StatementSlot> actions;
        ChestInputStation(GameTestHelper helper, Container input, BlockPos pos) {
            super(new BlockIndex(helper.absolutePos(pos)), Direction.UP);
            setLevel(helper.getLevel());
            this.input = input;
            StatementSlot action = new StatementSlot();
            action.statement = new ActionStationProvideItems();
            action.parameters = new IStatementParameter[0];
            actions = List.of(action);
        }
        @Override public Iterable<StatementSlot> getActiveActions() { return actions; }
        @Override public Container getItemInput() { return input; }
    }

    private static final class PipeOutputStation extends DockingStation {
        final IInjectable output;
        final List<StatementSlot> actions;
        PipeOutputStation(GameTestHelper helper, TilePipeHolder pipe) {
            super(new BlockIndex(pipe.getPipePos()), Direction.WEST);
            setLevel(helper.getLevel());
            StatementSlot action = new StatementSlot();
            action.statement = new ActionStationAcceptItems();
            action.parameters = new IStatementParameter[0];
            actions = List.of(action);
            output = new IInjectable() {
                @Override public boolean canInjectItems(Direction from) {
                    return from == Direction.WEST && !inFlight(pipe);
                }
                @Override public ItemStack injectItem(ItemStack stack, boolean execute, Direction from,
                                                      DyeColor colour, double speed) {
                    if (from != Direction.WEST || inFlight(pipe)) return stack;
                    return ((PipeFlowItems) pipe.getPipe().getFlow())
                        .injectItemFromRobotStation(stack.copy(), execute, from, colour, speed);
                }
            };
        }
        @Override public Iterable<StatementSlot> getActiveActions() { return actions; }
        @Override public IInjectable getItemOutput() { return output; }
        @Override public Direction getItemOutputSide() { return Direction.WEST; }
    }
}
