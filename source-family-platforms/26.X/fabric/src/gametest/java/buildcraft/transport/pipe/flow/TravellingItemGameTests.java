package buildcraft.transport.pipe.flow;

import java.util.EnumSet;

import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.gametest.PipeGameTestSupport;
import buildcraft.lib.BCLib;
public final class TravellingItemGameTests {
    private TravellingItemGameTests() {
    }

    public static void nbtRoundTripPreservesCargoColourMotionAndTriedSides(GameTestHelper helper) {
        ItemStack stack = new ItemStack(Items.DIAMOND_PICKAXE);
        stack.setDamageValue(37);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("in-flight test pickaxe"));
        net.minecraft.nbt.CompoundTag tag = ItemStackUtil.getCustomData(stack);
        tag.putString("buildcraft_test", "travelling_item");
        ItemStackUtil.setCustomData(stack, tag);

        TravellingItem original = new TravellingItem(stack.copy());
        original.colour = DyeColor.CYAN;
        original.toCenter = false;
        original.speed = 0.123;
        original.tickStarted = 100;
        original.tickFinished = 137;
        original.timeToDest = 37;
        original.side = Direction.SOUTH;
        original.tried = EnumSet.of(Direction.EAST, Direction.UP);
        original.isPhantom = true;

        CompoundTag nbt = original.writeToNbt(90, helper.getLevel().registryAccess());
        TravellingItem restored = new TravellingItem(nbt, 1_000, helper.getLevel().registryAccess());

        require(helper, ItemStack.matches(original.stack, restored.stack), "cargo stack changed after NBT round-trip");
        require(helper, restored.colour == DyeColor.CYAN, "cargo colour changed after NBT round-trip");
        require(helper, !restored.toCenter, "travel phase changed after NBT round-trip");
        require(helper, Math.abs(restored.speed - 0.123) < 1.0e-12, "speed changed after NBT round-trip");
        require(helper, restored.tickStarted == 1_010, "relative start tick was not restored");
        require(helper, restored.tickFinished == 1_047, "relative finish tick was not restored");
        require(helper, restored.timeToDest == 37, "timeToDest changed after NBT round-trip");
        require(helper, restored.side == Direction.SOUTH, "travel side changed after NBT round-trip");
        require(helper, restored.tried.equals(EnumSet.of(Direction.EAST, Direction.UP)),
            "tried-side set changed after NBT round-trip");
        require(helper, restored.isPhantom, "phantom flag changed after NBT round-trip");
        helper.succeed();
    }

    public static void mergeRequiresEquivalentCargoPathAndCloseArrivalTime(GameTestHelper helper) {
        TravellingItem first = item(new ItemStack(Items.APPLE, 10), DyeColor.RED, true, Direction.WEST, 100);
        TravellingItem compatible = item(new ItemStack(Items.APPLE, 5), DyeColor.RED, true, Direction.WEST, 103);
        require(helper, first.canMerge(compatible), "compatible travelling items refused to merge");
        require(helper, first.mergeWith(compatible), "mergeWith rejected compatible travelling items");
        require(helper, first.stack.getCount() == 15, "merge changed total item count");

        require(helper, !first.canMerge(item(new ItemStack(Items.APPLE), DyeColor.BLUE, true, Direction.WEST, 101)),
            "different colours merged");
        require(helper, !first.canMerge(item(new ItemStack(Items.APPLE), DyeColor.RED, false, Direction.WEST, 101)),
            "different travel phases merged");
        require(helper, !first.canMerge(item(new ItemStack(Items.APPLE), DyeColor.RED, true, Direction.EAST, 101)),
            "different travel sides merged");
        require(helper, !first.canMerge(item(new ItemStack(Items.APPLE), DyeColor.RED, true, Direction.WEST, 104)),
            "items four ticks apart merged");

        ItemStack named = new ItemStack(Items.APPLE);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("different NBT"));
        require(helper, !first.canMerge(item(named, DyeColor.RED, true, Direction.WEST, 101)),
            "items with different NBT merged");

        TravellingItem phantom = item(new ItemStack(Items.APPLE), DyeColor.RED, true, Direction.WEST, 101);
        phantom.isPhantom = true;
        require(helper, !first.canMerge(phantom), "phantom cargo merged");

        TravellingItem nearFull = item(new ItemStack(Items.APPLE, 63), null, true, Direction.WEST, 100);
        TravellingItem overflow = item(new ItemStack(Items.APPLE, 2), null, true, Direction.WEST, 101);
        require(helper, !nearFull.canMerge(overflow), "merge exceeded max stack size");
        helper.succeed();
    }

    private static TravellingItem item(ItemStack stack, DyeColor colour, boolean toCenter, Direction side,
        long finishTick) {
        TravellingItem item = new TravellingItem(stack);
        item.colour = colour;
        item.toCenter = toCenter;
        item.side = side;
        item.tickStarted = finishTick - 10;
        item.tickFinished = finishTick;
        item.timeToDest = 10;
        return item;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }
}
