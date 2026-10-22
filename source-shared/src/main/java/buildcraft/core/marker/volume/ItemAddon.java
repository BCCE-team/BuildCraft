/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.marker.volume;

import org.apache.commons.lang3.tuple.Pair;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
//? if <1.21.2 {
import net.minecraft.world.InteractionResultHolder;
//?}
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public abstract class ItemAddon extends Item {
    public ItemAddon(Properties prop) {
        super(prop);
    }

    public abstract Addon createAddon();

    /** Minecraft calls useOn before use when a real block is behind a virtual addon handle.
     * Handle both paths so an existing block does not intercept addon installation.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        return tryAttach(context.getLevel(), player, context.getHand());
    }

    //? if >=1.21.2 {
    @Override
    public InteractionResult use(Level world, Player player, InteractionHand hand) {
        return tryAttach(world, player, hand);
    }
    //?} else {
    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        return new InteractionResultHolder<>(tryAttach(world, player, hand), player.getItemInHand(hand));
    }
    //?}

    /** Exactly the original BuildCraft interaction: the item *only* attaches an addon
     * to an empty corner. Opening an installed addon belongs to the Marker Connector.
     * Never invoke onPlayerRightClick from here, including when installation fails.
     */
    private InteractionResult tryAttach(Level world, Player player, InteractionHand hand) {
        // Clients predict the interaction using their synced volume boxes, but the
        // server is authoritative about slot occupancy, locks and item consumption.
        java.util.List<VolumeBox> boxes = world.isClientSide
            ? ClientVolumeBoxes.INSTANCE.volumeBoxes
            : WorldSavedDataVolumeBoxes.get(world).volumeBoxes;
        Pair<VolumeBox, EnumAddonSlot> selected = EnumAddonSlot.getSelectingVolumeBoxAndSlot(player, boxes);
        VolumeBox box = selected.getLeft();
        EnumAddonSlot slot = selected.getRight();
        if (box == null || slot == null || box.isEditing() || box.addons.containsKey(slot)) {
            return InteractionResult.PASS;
        }
        if (box.getLockTargetsStream().anyMatch(target ->
            target instanceof Lock.Target.TargetResize ||
            (target instanceof Lock.Target.TargetAddon locked && locked.slot == slot))) {
            return InteractionResult.PASS;
        }
        Addon addon = createAddon();
        if (!addon.canBePlaceInto(box)) return InteractionResult.PASS;
        if (world.isClientSide) return InteractionResult.SUCCESS;

        addon.volumeBox = box;
        box.addons.put(slot, addon);
        addon.onAdded();
        // SavedData also sends a delta to clients so the actual addon appears.
        WorldSavedDataVolumeBoxes.get(world).setDirty();
        if (!player.getAbilities().instabuild) {
            player.getItemInHand(hand).shrink(1);
        }
        return InteractionResult.SUCCESS;
    }
}
