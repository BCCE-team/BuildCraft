/* Copyright (c) 2026 BuildCraft Community Edition contributors. MPL-2.0. */
package buildcraft.builders.item;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

/** Pure data for a client-rendered item tooltip. Safe to load on dedicated servers. */
public record BlueprintTooltipData(ItemStack stack) implements TooltipComponent {
    public BlueprintTooltipData {
        stack = stack.copy();
    }
}
