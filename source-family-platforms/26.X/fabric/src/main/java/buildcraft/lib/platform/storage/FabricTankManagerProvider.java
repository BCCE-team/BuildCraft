/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.platform.storage;

import javax.annotation.Nullable;
import buildcraft.lib.fluid.FabricTankManager;
import buildcraft.lib.fluid.FabricTankManagerStorage;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;

/**
 * Convenience contract for Fabric 26.3 block entities which own a
 * {@link FabricTankManager}. Implementations choose the manager exposed on
 * each side, preserving BuildCraft's per-face capability policy.
 */
public interface FabricTankManagerProvider extends FabricBlockStorageProvider {
    @Nullable
    FabricTankManager buildcraftTankManager(@Nullable Direction side);

    @Nullable
    default net.fabricmc.fabric.api.transfer.v1.storage.Storage<
        net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
    > buildcraftFluidStorage(@Nullable Direction side) {
        if (!(this instanceof BlockEntity tile) || tile.isRemoved()) return null;
        Level level = tile.getLevel();
        if (level == null || !level.hasChunkAt(tile.getBlockPos())) return null;
        FabricTankManager manager = buildcraftTankManager(side);
        if (manager == null) return null;
        FabricTankManagerStorage storage = manager.asFabricStorage(level.registryAccess());
        return storage;
    }
}
