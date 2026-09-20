package buildcraft.robotics.boards;

import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Small shared helpers for board-level inventory recovery. */
final class RobotBoardUtil {
    private RobotBoardUtil() {
    }

    /**
     * Drops the currently held stack into the world and clears the robot hand.
     *
     * <p>This is used when a board finds an item that it cannot use. Keeping that item equipped can otherwise make the
     * board repeatedly fail its work search forever, while replacing it through setItemInUse would silently delete the
     * held stack.</p>
     */
    static boolean dropHeldItem(EntityRobotBase robot) {
        if (robot == null) {
            return false;
        }

        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        if (held.isEmpty()) {
            return false;
        }

        ItemStack dropped = held.copy();
        robot.setItemInUse(ItemStack.EMPTY);
        if (!robot.level().isClientSide) {
            ItemEntity item = new ItemEntity(robot.level(), robot.getX(), robot.getY() + 0.25D, robot.getZ(), dropped);
            item.setDefaultPickUpDelay();
            robot.level().addFreshEntity(item);
        }
        return true;
    }
}
