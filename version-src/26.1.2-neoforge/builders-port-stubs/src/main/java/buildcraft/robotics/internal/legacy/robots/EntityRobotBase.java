package buildcraft.robotics.internal.legacy.robots;

import buildcraft.lib.internal.area.IZone;
import net.minecraft.world.item.ItemStack;

/**
 * Compile-only robotics boundary for the builders primary port. Runtime robot
 * behaviour remains owned by the robotics module and is intentionally not
 * bundled with builders.
 */
public abstract class EntityRobotBase {
    public abstract int getContainerSize();

    public abstract ItemStack getItem(int slot);

    public abstract ItemStack removeItem(int slot, int amount);

    public abstract IZone getZoneToWork();

    public abstract IRobotRegistry getRegistry();
}
