/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.builders.item;

import buildcraft.builders.BCBuildersBlocks;
import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/** Item wrapper kept separate so the construction marker can expose the classic recording predicate. */
public class ItemConstructionMarker extends BlockItem {
    private static final String TAG_RECORDING = "recording";

    public ItemConstructionMarker(Properties properties) {
        super(BCBuildersBlocks.CONSTRUCTION_MARKER.get(), properties);
    }

    public static boolean isRecording(ItemStack stack) {
        CompoundTag tag = ItemStackUtil.getCustomDataOrNull(stack);
        return tag != null && tag.getBoolean(TAG_RECORDING);
    }

    public static void setRecording(ItemStack stack, boolean recording) {
        CompoundTag tag = ItemStackUtil.getCustomData(stack);
        if (recording) {
            tag.putBoolean(TAG_RECORDING, true);
        } else {
            tag.remove(TAG_RECORDING);
        }
        ItemStackUtil.setCustomData(stack, tag);
    }
}
