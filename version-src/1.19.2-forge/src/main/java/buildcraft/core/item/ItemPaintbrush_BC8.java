/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.core.item;

import buildcraft.lib.internal.api.v2.BlockInteractionRuntime;
import buildcraft.lib.internal.block.ICustomPaintHandler;
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
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
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
        // A successful client prediction must not damage the stack or modify the world.
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        InteractionResult result = paintSignText(level, pos, player);
        if (result == InteractionResult.PASS) result = BlockInteractionRuntime.paint(
            level, pos, level.getBlockState(pos), ctx.getClickLocation(), ctx.getClickedFace(), type, player);
        if (result != InteractionResult.SUCCESS) return InteractionResult.FAIL;
        SoundUtil.playChangeColour(level, pos, type);
        if (player == null) return InteractionResult.SUCCESS;
        CompoundTag tag = stack.getTag();
        stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(hand));
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

    /**
     * Beds, signs, shulkers and BuildCraft paintable blocks can consume the
     * right-click before Item.useOn. Give the colored brush precedence so that
     * installed pipe lenses/facades get the same paint behavior as world blocks.
     */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getItemStack().getItem() instanceof ItemPaintbrush_BC8 brush) || brush.type == null) {
            return;
        }
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof SignBlockEntity)
                && !(state.getBlock() instanceof BedBlock
                || state.getBlock() instanceof ShulkerBoxBlock
                || state.getBlock() instanceof BannerBlock
                || state.getBlock() instanceof WallBannerBlock
                || state.getBlock() instanceof ICustomPaintHandler)) {
            return;
        }
        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.ALLOW);
    }

    /** In 1.19.2 a sign has a single text colour, preserved in the existing BlockEntity. */
    private InteractionResult paintSignText(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return InteractionResult.PASS;
        if (type == null || player == null || !player.mayBuild() || !level.mayInteract(player, pos)) {
            return InteractionResult.FAIL;
        }
        // SignBlockEntity#setColor preserves the lines and glowing-text flag and notifies clients.
        return sign.setColor(type) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
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