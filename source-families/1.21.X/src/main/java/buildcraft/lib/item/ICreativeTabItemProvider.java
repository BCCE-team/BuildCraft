package buildcraft.lib.item;

import java.util.function.Consumer;

import net.minecraft.world.item.ItemStack;

/**
 * Supplies all item stacks that should be exposed in a creative tab or in
 * BuildCraft's item-variant searches. Minecraft 1.20.1 no longer asks each
 * item to fill a {@code CreativeModeTab} directly, so variant-producing items
 * use this small compatibility contract instead.
 */
@FunctionalInterface
public interface ICreativeTabItemProvider {
    void addCreativeTabItems(Consumer<ItemStack> output);
}
