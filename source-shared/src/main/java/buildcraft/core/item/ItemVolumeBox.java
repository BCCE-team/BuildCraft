/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.item;

import buildcraft.core.marker.volume.WorldSavedDataVolumeBoxes;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
//? if >=1.21.5 {
import net.minecraft.world.item.component.TooltipDisplay;
//?}
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class ItemVolumeBox extends Item {
    public ItemVolumeBox(Properties pro) {
        super(pro);
    }
    
    /** The Volume Box item only creates a box. Resizing, confirming, cancelling and removing are
     * Marker Connector operations, as in the original BuildCraft implementation. */
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.PASS;
        BlockPos position = ctx.getClickedPos().relative(ctx.getClickedFace());
        WorldSavedDataVolumeBoxes boxes = WorldSavedDataVolumeBoxes.get(level);
        if (boxes.getVolumeBoxAt(position) != null) return InteractionResult.FAIL;
        boxes.addVolumeBox(position);
        boxes.setDirty();
        return InteractionResult.SUCCESS;
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
            lines.accept(Component.translatable("buildcraft.tooltip.volume_box." + index));
        }
    }
}
