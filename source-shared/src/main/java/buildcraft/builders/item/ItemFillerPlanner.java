/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.item;

import buildcraft.builders.addon.AddonFillerPlanner;
import buildcraft.core.marker.volume.Addon;
import buildcraft.core.marker.volume.ItemAddon;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
//? if >=1.21.5 {
import net.minecraft.world.item.component.TooltipDisplay;
//?}

public class ItemFillerPlanner extends ItemAddon {
    public ItemFillerPlanner(Properties prop) {
		super(prop);
	}

	@Override
    public Addon createAddon() {
        return new AddonFillerPlanner();
    }

    //? if >=1.21.5 {
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
        Consumer<Component> lines, TooltipFlag flag) {
    //?} else if >=1.21.1 {
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
        TooltipFlag flag) {
        Consumer<Component> lines = tooltip::add;
    //?} else {
    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.level.Level level, List<Component> tooltip,
        TooltipFlag flag) {
        Consumer<Component> lines = tooltip::add;
    //?}
        for (int index = 0; index < 3; ++index) {
            lines.accept(Component.translatable("buildcraft.tooltip.filler_planner." + index));
        }
    }
}
