package buildcraft.api.v2.machine;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/** Callbacks run on the owning level thread. Removal and invalidation are delivered at most once. */
public interface MachineComponentLifecycle {
    default void attached(MachineExecutionContext machine) { }
    default void restored() { }
    default void activated() { }
    default void suspended() { }
    default void removed() { }
    default void invalidated() { }
    /** Called before block removal; a component owns each stack it adds to the list. */
    default void addDrops(List<ItemStack> drops) { }
}
