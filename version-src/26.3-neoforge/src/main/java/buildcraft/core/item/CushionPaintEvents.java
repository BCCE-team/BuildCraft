/* Copyright (c) 2026 BuildCraft Community Edition contributors.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.core.item;

import buildcraft.core.BCCore;
import buildcraft.core.BCCoreItems;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.misc.SoundUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Minecraft 26.3 cushions are entities, not blocks: Item#useOn and the
 * block-paint handlers are never reached when a player right-clicks them.
 * Dye the existing entity so its passengers, position, name and persistent
 * data are preserved. This event listener is intentionally 26.3-only.
 */
@EventBusSubscriber(modid = BCCore.MODID)
public final class CushionPaintEvents {
    private CushionPaintEvents() {}

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Cushion cushion)
                || !(event.getItemStack().getItem() instanceof ItemPaintbrush_BC8 brush)) {
            return;
        }
        DyeColor paint = brush.getType();
        if (paint == null) {
            return; // An uncolored/clean brush is not a dye.
        }

        Player player = event.getEntity();
        Level level = event.getLevel();
        if (!player.mayBuild() || !level.mayInteract(player, cushion.blockPosition())) {
            return;
        }

        // Consume the interaction on both sides: otherwise Cushion#interact
        // seats the player before the brush has a chance to act.
        event.setCancellationResult(level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER);
        event.setCanceled(true);

        if (level.isClientSide() || cushion.getColor() == paint) {
            return; // Client prediction or same-color no-op: no damage/sound.
        }

        cushion.setColor(paint); // SyncedEntityData also persists to the cushion's saved data.
        SoundUtil.playChangeColour(level, cushion.blockPosition(), paint);

        ItemStack stack = event.getItemStack();
        CompoundTag tag = ItemStackUtil.getCustomDataOrNull(stack);
        EquipmentSlot slot = event.getHand() == InteractionHand.MAIN_HAND
                ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
        stack.hurtAndBreak(1, player, slot);
        if (stack.isEmpty()) {
            ItemStack cleanBrush = new ItemStack(BCCoreItems.PAINT_BRUSH.get());
            if (tag != null) {
                ItemStackUtil.setCustomData(cleanBrush, tag);
            }
            player.setItemInHand(event.getHand(), cleanBrush);
        }
        player.inventoryMenu.broadcastChanges();
    }
}
