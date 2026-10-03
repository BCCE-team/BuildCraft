package buildcraft.robotics;

import java.util.function.Supplier;
import net.minecraft.world.item.Item;

/** Compile-only Robotics item boundary; never included in the runtime artifact. */
public final class BCRoboticsItems {
    public static final Supplier<Item> REDSTONE_BOARD = () -> null;

    private BCRoboticsItems() {
    }
}
