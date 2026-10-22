/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.core.item;

import buildcraft.lib.internal.api.v2.BlockInteractionRuntime;
import buildcraft.lib.internal.block.ICustomPaintHandler;
import buildcraft.core.BCCoreItems;
import buildcraft.lib.item.ItemByEnum;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.misc.SoundUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.WallBannerBlock;
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

        // Client-side prediction cannot modify block entities or durability.
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        InteractionResult result = paintSignText(level, pos, player);
        if (result == InteractionResult.PASS) result = BlockInteractionRuntime.paint(
            level, pos, level.getBlockState(pos), hitPos, ctx.getClickedFace(), type, player
        );
        if (result != InteractionResult.SUCCESS) {
            return InteractionResult.FAIL;
        }
        SoundUtil.playChangeColour(level, pos, type);
        if (player == null) {
            return InteractionResult.SUCCESS;
        }

        CompoundTag tag = ItemStackUtil.getCustomDataOrNull(stack);
        EquipmentSlot slot = hand == InteractionHand.MAIN_HAND
                ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
        stack.hurtAndBreak(1, player, slot);
        if (stack.isEmpty()) {
            ItemStack cleanBrush = new ItemStack(BCCoreItems.PAINT_BRUSH.get());
            if (tag != null) {
                ItemStackUtil.setCustomData(cleanBrush, tag);
            }
            player.setItemInHand(hand, cleanBrush);
        }
        player.inventoryMenu.broadcastChanges();
        return InteractionResult.SUCCESS;
    }

    /**
     * Loader-neutral click priority decision. Beds, signs, shulkers and
     * BuildCraft paintable blocks can consume interaction before Item.useOn.
     * NeoForge translates this decision into its native useBlock/useItem flags.
     */
    public static boolean shouldOverrideBlockUse(ItemStack brushStack, Level level, BlockPos pos) {
        if (!(brushStack.getItem() instanceof ItemPaintbrush_BC8 brush) || brush.type == null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return level.getBlockEntity(pos) instanceof SignBlockEntity
                || state.getBlock() instanceof BedBlock
                || state.getBlock() instanceof ShulkerBoxBlock
                || state.getBlock() instanceof BannerBlock
                || state.getBlock() instanceof WallBannerBlock
                || state.getBlock() instanceof ICustomPaintHandler;
    }

    /** Paint only the visible sign face, preserving its lines, formatting and glowing effect. */
    private InteractionResult paintSignText(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return InteractionResult.PASS;
        if (type == null || player == null || !player.mayBuild() || !level.mayInteract(player, pos)
                || sign.isWaxed()) return InteractionResult.FAIL;
        //? if >=26.3 {
        net.minecraft.world.level.block.entity.SignTextSlot face = sign.getSlotPlayerIsFacing(player);
        SignText current = sign.getText(face);
        if (current.getColor() == type) return InteractionResult.FAIL;
        return sign.updateText(text -> text.withColor(type), face)
            ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        //?} else {
        boolean front = sign.isFacingFrontText(player);
        SignText current = sign.getText(front);
        if (current.getColor() == type) return InteractionResult.FAIL;
        return sign.updateText(text -> text.setColor(type), front)
            ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        //?}
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