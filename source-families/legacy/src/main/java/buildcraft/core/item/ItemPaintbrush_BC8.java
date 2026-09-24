/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.core.item;

import buildcraft.lib.internal.api.v2.BlockInteractionRuntime;
import buildcraft.core.BCCoreItems;
import buildcraft.lib.item.ItemByEnum;
import buildcraft.lib.misc.SoundUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class ItemPaintbrush_BC8 extends ItemByEnum<DyeColor> {
    
    public ItemPaintbrush_BC8(Properties pro, DyeColor color) {
		super(pro, color);
    }
		
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        InteractionHand hand = ctx.getHand();
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        ItemStack stack = ctx.getItemInHand();
        Vec3 hitPos = ctx.getClickLocation();

        InteractionResult result = BlockInteractionRuntime.paint(
            level, pos, level.getBlockState(pos), hitPos, ctx.getClickedFace(), type, player
        );
        if (result != InteractionResult.SUCCESS) {
            return InteractionResult.FAIL;
        }
        if (player == null) {
            return InteractionResult.SUCCESS;
        }

        CompoundTag tag = stack.getTag();
        stack.hurtAndBreak(1, player, broken -> broken.broadcastBreakEvent(hand));
        if (stack.isEmpty()) {
            ItemStack cleanBrush = new ItemStack(BCCoreItems.PAINT_BRUSH.get());
            cleanBrush.setTag(tag);
            player.setItemInHand(hand, cleanBrush);
        }
        player.inventoryMenu.broadcastChanges();
        return InteractionResult.SUCCESS;
    }

    @Override
    public void setDamage(ItemStack stack, int damage) {
        super.setDamage(stack, damage);
    }

    public boolean tryBrush(ItemStack stack, Level world, BlockPos pos, BlockState state, Vec3 hitPos, Direction side, Player player) {
        if (type != null && stack.getDamageValue() > 64) {
            return false;
        }

        InteractionResult result = BlockInteractionRuntime.paint(world, pos, state, hitPos, side, type, player);

        if (result == InteractionResult.SUCCESS) {
//            ParticleUtil.showChangeColour(world, hitPos, colour);
            SoundUtil.playChangeColour(world, pos, type);
            return true;
        }
        return false;
    }
}