package dev.bcce.apifixture;

import buildcraft.api.v2.template.TemplateHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Example of a template item that is not a vanilla BlockItem. */
public final class TemplatePlacementFixtureAddon implements TemplateHandler {
    public int executions;

    @Override
    public boolean acceptsItem(ItemStack stack) {
        return stack != null && stack.getCount() == 3;
    }

    @Override
    public boolean handle(Level level, BlockPos pos, Player actor, ItemStack stack) {
        if (!acceptsItem(stack)) return false;
        executions++;
        return true;
    }
}
