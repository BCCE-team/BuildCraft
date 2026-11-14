/* Copyright (c) 2026 BuildCraft Community Edition contributors.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.core.item;

import buildcraft.core.BCCoreItems;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.misc.SoundUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

import org.jetbrains.annotations.Nullable;

/**
 * Minecraft 26.3-specific entity painting. A Cushion is an entity rather
 * than a block and must be recolored in place, without losing passengers,
 * location, custom name or serialized entity data.
 */
public final class CushionPaintEvents {
    private CushionPaintEvents() {}

    public static InteractionResult onInteract(Player player, Level level, InteractionHand hand,
                                               Entity entity, @Nullable EntityHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(entity instanceof Cushion cushion)
                || !(stack.getItem() instanceof ItemPaintbrush_BC8 brush)) {
            return InteractionResult.PASS;
        }
        DyeColor paint = brush.getType();
        if (paint == null || player.isSpectator() || !player.mayBuild()
                || !level.mayInteract(player, cushion.blockPosition())) {
            return InteractionResult.PASS;
        }

        // Suppress Cushion#interact on both logical sides, otherwise the click
        // seats the player before the brush can apply the new entity color.
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (cushion.getColor() == paint) return InteractionResult.SUCCESS_SERVER;

        cushion.setColor(paint);
        SoundUtil.playChangeColour(level, cushion.blockPosition(), paint);
        CompoundTag tag = ItemStackUtil.getCustomDataOrNull(stack);
        EquipmentSlot slot = hand == InteractionHand.MAIN_HAND
            ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
        stack.hurtAndBreak(1, player, slot);
        if (stack.isEmpty()) {
            ItemStack cleanBrush = new ItemStack(BCCoreItems.PAINT_BRUSH.get());
            if (tag != null) ItemStackUtil.setCustomData(cleanBrush, tag);
            player.setItemInHand(hand, cleanBrush);
        }
        player.inventoryMenu.broadcastChanges();
        return InteractionResult.SUCCESS_SERVER;
    }
}
