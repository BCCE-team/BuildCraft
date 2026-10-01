/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class RequiredExtractorItemsList extends RequiredExtractor {
    private NbtPath path = null;

    public RequiredExtractorItemsList() {
    }

    public RequiredExtractorItemsList(NbtPath path) {
        this.path = path;
    }

    public NbtPath getPath() {
        return path;
    }

    @Nonnull
    @Override
    public List<ItemStack> extractItemsFromBlock(@Nonnull BlockState blockState, @Nullable CompoundTag tileNbt, Level level) {
        if (path == null || tileNbt == null) {
            return Collections.emptyList();
        }
        return Optional.ofNullable(path.get(tileNbt))
            .map(NBTUtilBC::readCompoundList)
            .map(stream -> stream.map(tag -> ItemStackUtil.parseOptional(level.registryAccess(), tag)).collect(Collectors.toList()))
            .map(Collections::unmodifiableList)
            .orElseGet(Collections::emptyList);
    }

    @Nonnull
    @Override
    public List<ItemStack> extractItemsFromEntity(@Nonnull CompoundTag entityNbt, Level level) {
        if (path == null) {
            return Collections.emptyList();
        }
        return Optional.ofNullable(path.get(entityNbt))
            .map(NBTUtilBC::readCompoundList)
            .map(stream -> stream.map(tag -> ItemStackUtil.parseOptional(level.registryAccess(), tag)).collect(Collectors.toList()))
            .map(Collections::unmodifiableList)
            .orElseGet(Collections::emptyList);
    }
}
