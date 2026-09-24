/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.lib.tile.item;

import javax.annotation.Nonnull;

import buildcraft.lib.platform.storage.MutableItemStorage;
import net.minecraft.world.item.ItemStack;

/** Loader-neutral callback for BuildCraft-owned mutable inventories. */
@FunctionalInterface
public interface StackChangeCallback {
    void onStackChange(MutableItemStorage itemHandler, int slot, @Nonnull ItemStack before, @Nonnull ItemStack after);
}
