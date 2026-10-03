package buildcraft.robotics.item;

import buildcraft.robotics.BCRoboticsBoards.BoardEntry;
import net.minecraft.world.item.ItemStack;

/** Compile-only bridge for Programming Table until Robotics is ported. */
public final class ItemRedstoneBoard {
    private ItemRedstoneBoard() {
    }

    public static ItemStack createStack(BoardEntry entry) {
        return ItemStack.EMPTY;
    }
}
