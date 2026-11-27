/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.robotics.client;

import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.item.ItemRobot;
import net.minecraft.world.item.ItemStack;

/** Display only. The item's stored charge is the same value used by its tooltip and placement. */
public final class RobotBatteryVisuals {
    private RobotBatteryVisuals() { }

    public static boolean powered(ItemStack stack) {
        return ItemRobot.getEnergy(stack) > 0;
    }

    public static float powerModel(ItemStack stack) {
        return powered(stack) ? 1.0F : 0.0F;
    }

    public static int energyFromPercent(int percent) {
        return (int) ((long) Math.max(0, Math.min(100, percent)) * EntityRobotBase.MAX_ENERGY / 100L);
    }

    public static int brightness(int energy) {
        int clamped = Math.max(0, Math.min(EntityRobotBase.MAX_ENERGY, energy));
        return (int) ((255L * clamped + EntityRobotBase.MAX_ENERGY / 2L) / EntityRobotBase.MAX_ENERGY);
    }

    public static int sideTint(ItemStack stack) {
        // Item/GUI colors multiply the red texture by RGB, not by alpha.
        int value = brightness(ItemRobot.getEnergy(stack));
        return 0xFF000000 | value * 0x010101;
    }

    public static int itemColor(ItemStack stack, int tintIndex) {
        return tintIndex == 0 ? sideTint(stack) : 0xFFFFFFFF;
    }
}
