/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.robotics.item;

import java.util.List;
import java.util.concurrent.TimeUnit;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
//? if >=1.21.1 {
import buildcraft.lib.misc.ItemStackUtil;
//?}

/** A render-only marker for the cached Robotics creative-tab icon. */
public final class RobotCreativeTabIcon {
    private static final String ICON_KEY = "bc_robot_creative_tab_icon";
    private static final long START_NANOS = System.nanoTime();
    private static final long PERIOD_MILLIS = 1000L;
    private static volatile List<Float> professionModels = List.of();

    private RobotCreativeTabIcon() { }

    public static ItemStack create() {
        // Built-in, non-empty professions have dedicated positive model values.
        // Addon programs using the generic empty-robot model must not enter this cycle.
        professionModels = BCRoboticsBoards.robotEntries().stream()
                .map(BCRoboticsBoards.BoardEntry::modelValue)
                .filter(value -> value > 0.0F).distinct().sorted().toList();
        ItemStack stack = ItemRobot.createRobotStack(BCRoboticsBoards.getByKey("picker"),
                EntityRobotBase.MAX_ENERGY);
        //? if >=1.21.1 {
        CompoundTag tag = ItemStackUtil.getCustomData(stack);
        tag.putBoolean(ICON_KEY, true);
        ItemStackUtil.setCustomData(stack, tag);
        //?} else {
        stack.getOrCreateTag().putBoolean(ICON_KEY, true);
        //?}
        return stack;
    }

    /** Called only by the existing client item-model property, not by robot AI or placement. */
    public static float modelValue(ItemStack stack) {
        //? if >=1.21.1 {
        CompoundTag tag = ItemStackUtil.getCustomData(stack);
        //?} else {
        CompoundTag tag = stack.getTag();
        //?}
        if (tag != null && tag.contains(ICON_KEY)) {
            List<Float> models = professionModels;
            if (!models.isEmpty()) {
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - START_NANOS);
                return modelAtMillis(models, elapsed);
            }
        }
        return BCRoboticsBoards.getRobotModelValue(stack);
    }

    static float modelAtMillis(List<Float> models, long elapsedMillis) {
        long slot = Math.floorDiv(elapsedMillis, PERIOD_MILLIS);
        return models.get(Math.floorMod(slot, models.size()));
    }
}
