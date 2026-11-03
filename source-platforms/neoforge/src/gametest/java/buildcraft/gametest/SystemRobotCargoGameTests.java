package buildcraft.gametest;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.component.DataComponents;
import buildcraft.lib.misc.ItemStackUtil;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import buildcraft.lib.BCLib;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotLoad;
import buildcraft.robotics.ai.AIRobotUnload;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.statements.ActionStationProvideItems;
import buildcraft.robotics.statements.ActionStationAcceptItems;

/**
 * System-level cargo lifecycle through real station permission checks, inventory handlers,
 * a real robot inventory, partial output acceptance and retry. The AI load/unload routines
 * are driven explicitly here; separate client/robot-scheduler integration tests test travel.
 */
@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class SystemRobotCargoGameTests {
    private static final String TEMPLATE = PipeGameTestSupport.LARGE_EMPTY_TEMPLATE;
    private SystemRobotCargoGameTests() {}

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 100)
    public static void carrierPartiallyDeliversTaggedStockAndRetriesWithoutDuplication(GameTestHelper helper) {
        ChestBlockEntity source = placeChest(helper, new BlockPos(1, 1, 3));
        ChestBlockEntity destination = placeChest(helper, new BlockPos(5, 1, 3));
        ItemStack original = withExternalData(new ItemStack(Items.APPLE, 20), "partial-cargo", 7_654_321L);
        source.setItem(0, original.copy());
        source.setChanged();
        for (int i = 0; i < destination.getContainerSize(); i++) {
            destination.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        ItemStack existing = original.copy();
        existing.setCount(58);
        destination.setItem(0, existing);
        destination.setChanged();

        EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        TestStation input = new TestStation(helper, new BlockPos(2, 1, 3), source, Direction.WEST, true);
        TestStation output = new TestStation(helper, new BlockPos(4, 1, 3), destination, Direction.EAST, false);
        require(helper, AIRobotLoad.load(robot, input, stack -> sameItemAndData(stack, original), 20, false),
            "station simulation rejected valid cargo");
        require(helper, source.getItem(0).getCount() == 20 && count(robot, original) == 0,
            "SIMULATE mutated chest or robot");
        require(helper, AIRobotLoad.load(robot, input, stack -> sameItemAndData(stack, original), 20, true),
            "carrier did not load from the real source chest");
        require(helper, source.getItem(0).isEmpty() && count(robot, original) == 20,
            "load did not transfer the correct amount or preserve item identity");
        require(helper, AIRobotUnload.unload(robot, output, true, true),
            "carrier did not perform its first partial delivery");
        require(helper, count(destination, original) == 64 && count(robot, original) == 14,
            "first partial delivery lost items, duplicated items or changed components");

        destination.setItem(1, ItemStack.EMPTY);
        destination.setChanged();
        require(helper, AIRobotUnload.unload(robot, output, true, true),
            "carrier refused to retry when the receiver freed capacity");
        require(helper, count(destination, original) == 78 && count(robot, original) == 0,
            "second transfer did not conserve all 20 original tagged items");
        require(helper, count(destination, original) - 58 == 20,
            "result includes a different amount from the original source");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 100)
    public static void robotTransfersDamagedEnergyBearingWeaponAcrossStations(GameTestHelper helper) {
        ChestBlockEntity source = placeChest(helper, new BlockPos(1, 1, 3));
        ChestBlockEntity target = placeChest(helper, new BlockPos(5, 1, 3));
        ItemStack weapon = withExternalData(new ItemStack(Items.DIAMOND_SWORD), "external-weapon", 12_345_678L);
        weapon.setDamageValue(234);
        setCustomName(weapon, "Preserved charged blade");
        ItemStack expected = weapon.copy();
        source.setItem(0, weapon);
        source.setChanged();
        EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        TestStation pickup = new TestStation(helper, new BlockPos(2, 1, 3), source, Direction.WEST, true);
        TestStation delivery = new TestStation(helper, new BlockPos(4, 1, 3), target, Direction.EAST, false);

        require(helper, AIRobotLoad.load(robot, pickup, stack -> sameItemAndData(stack, expected), 1, true),
            "robot could not load the custom weapon");
        require(helper, source.getItem(0).isEmpty(), "source was not debited after loading weapon");
        require(helper, count(robot, expected) == 1, "robot changed the weapon during loading");
        require(helper, AIRobotUnload.unload(robot, delivery, true, true), "robot could not deliver the weapon");
        require(helper, count(robot, expected) == 0 && count(target, expected) == 1,
            "weapon identity, damage, custom name or serialized charge was lost");
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = TEMPLATE, timeoutTicks = 100)
    public static void chargedItemVariantsNeverMergeAcrossRobotDelivery(GameTestHelper helper) {
        ChestBlockEntity source = placeChest(helper, new BlockPos(1, 1, 3));
        ChestBlockEntity target = placeChest(helper, new BlockPos(5, 1, 3));
        ItemStack first = withExternalData(new ItemStack(Items.APPLE, 8), "energy-A", 400L);
        ItemStack second = withExternalData(new ItemStack(Items.APPLE, 7), "energy-B", 900L);
        source.setItem(0, second.copy());
        target.setItem(0, first.copy());
        source.setChanged(); target.setChanged();
        EntityRobot robot = new EntityRobot(helper.getLevel(), BCRoboticsBoards.EMPTY);
        TestStation pickup = new TestStation(helper, new BlockPos(2, 1, 3), source, Direction.WEST, true);
        TestStation delivery = new TestStation(helper, new BlockPos(4, 1, 3), target, Direction.EAST, false);

        require(helper, AIRobotLoad.load(robot, pickup, stack -> sameItemAndData(stack, second), 7, true),
            "robot did not load independently tagged stack");
        require(helper, AIRobotUnload.unload(robot, delivery, true, true),
            "robot did not unload the second tagged stack");
        require(helper, count(target, first) == 8 && count(target, second) == 7,
            "robot combined component-incompatible items or lost one variant");
        require(helper, target.getItem(0).getCount() == 8,
            "variant with a different external energy component was illegally merged");
        require(helper, count(robot, first) == 0 && count(robot, second) == 0,
            "completed robot delivery retained an orphan payload");
        helper.succeed();
    }

    private static ChestBlockEntity placeChest(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.CHEST.defaultBlockState());
        if (!(getBlockEntity(helper, pos) instanceof ChestBlockEntity chest)) {
            helper.fail("missing real chest at " + pos);
            throw new IllegalStateException("missing chest");
        }
        return chest;
    }

    private static int count(Container c, ItemStack template) {
        int total = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && sameItemAndData(s, template)) total += s.getCount();
        }
        return total;
    }

    private static class TestStation extends DockingStation {
        private final Container inventory;
        private final Direction inventorySide;
        private final List<StatementSlot> actions;

        TestStation(GameTestHelper helper, BlockPos pos, Container inventory, Direction side, boolean provide) {
            super(new BlockIndex(helper.absolutePos(pos)), Direction.UP);
            this.inventory = inventory;
            this.inventorySide = side;
            this.setLevel(helper.getLevel());
            StatementSlot slot = new StatementSlot();
            slot.statement = provide ? new ActionStationProvideItems() : new ActionStationAcceptItems();
            slot.parameters = new IStatementParameter[0];
            this.actions = List.of(slot);
        }
        @Override public Iterable<StatementSlot> getActiveActions() { return actions; }
        @Override public Container getItemInput() { return inventory; }
        @Override public Direction getItemInputSide() { return inventorySide; }
        @Override public Direction getItemOutputSide() { return inventorySide; }
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }

    private static net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(GameTestHelper helper, BlockPos pos) { return GameTestCompat.getBlockEntity(helper, pos); }
    private static boolean sameItemAndData(ItemStack a, ItemStack b) { return ItemStack.isSameItemSameComponents(a, b); }
    private static void setCustomName(ItemStack item, String name) { item.set(DataComponents.CUSTOM_NAME, Component.literal(name)); }
    private static ItemStack withExternalData(ItemStack item, String key, long energy) {
        CompoundTag data = ItemStackUtil.getCustomData(item);
        CompoundTag ext = new CompoundTag();
        ext.putString("external_id", key);
        ext.putLong("stored_energy", energy);
        data.put("external_mod_state", ext);
        ItemStackUtil.setCustomData(item, data);
        return item;
    }
}
