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
import net.minecraft.world.level.Level;

public abstract class ItemAddon extends Item {
    public ItemAddon(Properties prop) {
        super(prop);
    }

    public abstract Addon createAddon();

    //? if >=1.21.2 {
    @Override
    public InteractionResult use(Level world, Player player, InteractionHand hand) {
        return interactWithVolumeBox(world, player, hand);
    }
    //?} else {
    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        return new InteractionResultHolder<>(interactWithVolumeBox(world, player, hand), player.getItemInHand(hand));
    }
    //?}

    private InteractionResult interactWithVolumeBox(Level world, Player player, InteractionHand hand) {
        if (world.isClientSide) {
            return InteractionResult.PASS;
        }

        WorldSavedDataVolumeBoxes volumeBoxes = WorldSavedDataVolumeBoxes.get(world);
        Pair<VolumeBox, EnumAddonSlot> selectingVolumeBoxAndSlot = EnumAddonSlot.getSelectingVolumeBoxAndSlot(
            player,
            volumeBoxes.volumeBoxes
        );
        VolumeBox volumeBox = selectingVolumeBoxAndSlot.getLeft();
        EnumAddonSlot slot = selectingVolumeBoxAndSlot.getRight();
        if (volumeBox != null && slot != null && !volumeBox.isEditing()) {
            if (volumeBox.addons.get(slot) != null &&
                volumeBox.addons.get(slot).getClass() == createAddon().getClass() && !player.isCrouching()) {
                volumeBox.addons.get(slot).onPlayerRightClick(player);
                return InteractionResult.SUCCESS;
            }
            if (!volumeBox.addons.containsKey(slot) &&
                volumeBox.getLockTargetsStream().noneMatch(target ->
                    target instanceof Lock.Target.TargetResize ||
                    (target instanceof Lock.Target.TargetAddon lock && lock.slot == slot))) {
                Addon addon = createAddon();
                if (addon.canBePlaceInto(volumeBox)) {
                    addon.volumeBox = volumeBox;
                    volumeBox.addons.put(slot, addon);
                    volumeBox.addons.get(slot).onAdded();
                    volumeBoxes.setDirty();
                    if (!player.getAbilities().instabuild) {
                        player.getItemInHand(hand).shrink(1);
                    }
                    return InteractionResult.SUCCESS;
                }
            }
        }

        return InteractionResult.PASS;
	}

}
