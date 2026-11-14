/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.platform.storage;

import javax.annotation.Nullable;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.Direction;

/**
 * Fabric-side block-entity contract for BuildCraft-owned transfer endpoints.
 *
 * <p>Implementations return the actual side view, not a synthetic slot list.
 * Return {@code null} when the requested side has no endpoint. Returned
 * storages must participate in the transaction supplied to their insert and
 * extract methods and retain native item/fluid Data Components.</p>
 */
public interface FabricBlockStorageProvider {
    @Nullable
    default Storage<ItemVariant> buildcraftItemStorage(@Nullable Direction side) {
        return null;
    }

    @Nullable
    default Storage<FluidVariant> buildcraftFluidStorage(@Nullable Direction side) {
        return null;
    }

    @Nullable
    default team.reborn.energy.api.EnergyStorage buildcraftEnergyStorage(@Nullable Direction side) {
        return null;
    }
}
