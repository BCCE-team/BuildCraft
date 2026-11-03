package buildcraft.gametest;

import java.util.List;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.ai.AIRobotLoad;
import buildcraft.robotics.ai.AIRobotUnload;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.statements.ActionStationProvideItems;
import buildcraft.robotics.statements.ActionStationAcceptItems;
import buildcraft.transport.BCTransportPipes;
import buildcraft.transport.internal.IInjectable;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import buildcraft.lib.misc.ItemStackUtil;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Real robot cargo -> BCCE travelling items -> storage integration.
 * Only AI task selection/navigation is replaced by a deterministic station fixture.
 * Each handoff still runs through AIRobotLoad, AIRobotUnload, the normal item pipe
 * transport implementation and a real destination ChestBlockEntity.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemRobotPipeDeliveryGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private SystemRobotPipeDeliveryGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 430)
    public static void enchantedChargedToolSurvivesRobotPipeWarehouseHandoffs(GameTestHelper helper) {
        ChestBlockEntity source = chest(helper, new BlockPos(1, 1, 3));
        ChestBlockEntity warehouse = chest(helper, new BlockPos(4, 1, 3));
        TilePipeHolder first = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.cobbleItem);
        TilePipeHolder second = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.goldItem);
        ItemStack weapon = chargedWeapon(helper, 123_456_789L, "enchantment-cargo");
        ItemStack expected = weapon.copy();
        source.setItem(0, weapon);
        source.setChanged();
        EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        ChestInputStation load = new ChestInputStation(helper, source);
        PipeOutputStation unload = new PipeOutputStation(helper, first);

        helper.runAfterDelay(8, () -> {
            require(helper, AIRobotLoad.load(robot, load, stack -> same(stack, expected), 1, false),
                "preflight rejected the enchanted and charged inventory item");
            require(helper, count(source, expected) == 1 && count(robot, expected) == 0,
                "simulation changed the real inventory or robot cargo");
            require(helper, AIRobotLoad.load(robot, load, stack -> same(stack, expected), 1, true),
                "real robot loading failed");
            require(helper, AIRobotUnload.unload(robot, unload, true, true),
                "robot could not inject its actual payload into the pipe network");
            require(helper, count(source, expected) == 0 && count(robot, expected) == 0,
                "source and robot ledgers did not transfer the exact item");
        });
        helper.runAfterDelay(355, () -> {
            require(helper, count(warehouse, expected) == 1,
                "pipe delivery altered enchantments, damage, custom name, charge or item identity");
            require(helper, !((PipeFlowItems) first.getPipe().getFlow()).doesContainItems()
                && !((PipeFlowItems) second.getPipe().getFlow()).doesContainItems(),
                "completed robot-to-pipe delivery left stuck cargo");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 570)
    public static void robotRetriesBusyPipeWithDistinctChargedStackVariants(GameTestHelper helper) {
        ChestBlockEntity source = chest(helper, new BlockPos(1, 1, 3));
        ChestBlockEntity warehouse = chest(helper, new BlockPos(4, 1, 3));
        TilePipeHolder first = PipeGameTestSupport.placePipe(helper, new BlockPos(2, 1, 3), BCTransportPipes.cobbleItem);
        TilePipeHolder last = PipeGameTestSupport.placePipe(helper, new BlockPos(3, 1, 3), BCTransportPipes.cobbleItem);
        ItemStack one = chargedWeapon(helper, 400L, "unique-A");
        ItemStack two = chargedWeapon(helper, 900L, "unique-B");
        source.setItem(0, one.copy());
        source.setItem(1, two.copy());
        source.setChanged();
        EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        ChestInputStation load = new ChestInputStation(helper, source);
        PipeOutputStation unload = new PipeOutputStation(helper, first);

        helper.runAfterDelay(8, () -> {
            require(helper, AIRobotLoad.load(robot, load, stack -> true, 2, true),
                "robot could not load both custom-component variants");
            require(helper, count(robot, one) == 1 && count(robot, two) == 1,
                "robot merged incompatible component stacks during extraction");
            require(helper, AIRobotUnload.unload(robot, unload, true, true), "first robot injection failed");
            require(helper, !AIRobotUnload.unload(robot, unload, true, true),
                "busy pipe accepted a second travelling payload during its guarded window");
            require(helper, count(robot, one) + count(robot, two) == 1,
                "rejected second cargo was lost from the robot");
        });
        helper.runAfterDelay(240, () -> {
            require(helper, count(warehouse, one) + count(warehouse, two) == 1,
                "first robot parcel was not delivered with full components");
            require(helper, AIRobotUnload.unload(robot, unload, true, true),
                "robot failed to retry when the pipe became available");
        });
        helper.runAfterDelay(490, () -> {
            require(helper, count(source, one) + count(source, two) == 0,
                "source was not debited for both items");
            require(helper, count(robot, one) + count(robot, two) == 0,
                "robot retained an orphan parcel after second delivery");
            require(helper, count(warehouse, one) == 1 && count(warehouse, two) == 1,
                "two separate charge/enchantment variants were merged, altered or lost");
            require(helper, !((PipeFlowItems) first.getPipe().getFlow()).doesContainItems()
                && !((PipeFlowItems) last.getPipe().getFlow()).doesContainItems(),
                "outstanding travelling item after the retry window");
            helper.succeed();
        });
    }

    private static class ChestInputStation extends DockingStation {
        private final Container input;
        private final List<StatementSlot> actions;
        ChestInputStation(GameTestHelper helper, Container input) {
            super(new BlockIndex(helper.absolutePos(new BlockPos(1, 1, 3))), Direction.UP);
            setLevel(helper.getLevel());
            this.input = input;
            StatementSlot statement = new StatementSlot();
            statement.statement = new ActionStationProvideItems();
            statement.parameters = new IStatementParameter[0];
            actions = List.of(statement);
        }
        @Override public Iterable<StatementSlot> getActiveActions() { return actions; }
        @Override public Container getItemInput() { return input; }
    }

    private static class PipeOutputStation extends DockingStation {
        private final IInjectable injector;
        private final List<StatementSlot> actions;
        PipeOutputStation(GameTestHelper helper, TilePipeHolder pipe) {
            super(new BlockIndex(pipe.getPipePos()), Direction.WEST);
            setLevel(helper.getLevel());
            StatementSlot statement = new StatementSlot();
            statement.statement = new ActionStationAcceptItems();
            statement.parameters = new IStatementParameter[0];
            actions = List.of(statement);
            injector = new IInjectable() {
                @Override public boolean canInjectItems(Direction from) {
                    return from == Direction.WEST && !((PipeFlowItems) pipe.getPipe().getFlow()).doesContainItems();
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
        @Override public IInjectable getItemOutput() { return injector; }
        @Override public Direction getItemOutputSide() { return Direction.WEST; }
    }

    private static ItemStack chargedWeapon(GameTestHelper helper, long energy, String identity) {
        ItemStack weapon = new ItemStack(Items.DIAMOND_SWORD);
        weapon.setDamageValue(78);
        weapon.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            .getOrThrow(Enchantments.UNBREAKING), 2);
        weapon.set(DataComponents.CUSTOM_NAME, Component.literal("BCCE test quantum blade"));
        CompoundTag tag = ItemStackUtil.getCustomData(weapon);
        tag.putString("stack_identity", identity);
        tag.putLong("stored_charge", energy);
        ItemStackUtil.setCustomData(weapon, tag);
        return weapon;
    }

    private static boolean same(ItemStack stack, ItemStack other) {
        return ItemStack.isSameItemSameComponents(stack, other);
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

    private static int count(Container container, ItemStack matching) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && same(stack, matching)) count += stack.getCount();
        }
        return count;
    }

    private static void require(GameTestHelper helper, boolean ok, String message) {
        if (!ok) helper.fail(message);
    }
}
