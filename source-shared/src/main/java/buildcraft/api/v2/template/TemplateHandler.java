package buildcraft.api.v2.template;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

@FunctionalInterface
public interface TemplateHandler {
    boolean handle(Level level, BlockPos pos, Player actor, ItemStack stack);

    /** Opt in to template construction with addon items that are not BlockItems. */
    default boolean acceptsItem(ItemStack stack) { return false; }
}
