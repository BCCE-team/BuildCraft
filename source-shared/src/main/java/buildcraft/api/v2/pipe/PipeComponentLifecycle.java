package buildcraft.api.v2.pipe;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * The pipe calls attached before either restored or placed, then activated when loaded.
 * Chunk unload suspends execution; reloading may activate the same instance again.
 * Permanent destruction calls removed once, followed by invalidated.
 * Loot evaluation is read-only and may run before or after removal.
 */
public interface PipeComponentLifecycle extends PipeComponent {
    default void attached(PipeView pipe) { }
    default void restored() { }
    default void activated() { }
    default void placed() { }
    default void connectionsChanged(PipeConnectionChange change) { }
    default void suspended() { }
    /** Called on permanent destruction, not on chunk unload. */
    default void removed() { }
    /** May run more than once while loot is evaluated; must not consume state. */
    default void addDrops(List<ItemStack> drops, int fortune) { }
    default void invalidated() { }
}
