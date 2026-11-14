/*
 * Copyright (c) 2026 the BuildCraft Community Edition contributors.
 * Licensed under the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.misc;

import javax.annotation.Nullable;
import buildcraft.lib.platform.capability.BlockCapability;
import buildcraft.lib.platform.capability.EntityCapability;
import buildcraft.lib.compat.neoforge263.energy.IEnergyStorage;
import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler;
import buildcraft.lib.compat.neoforge263.items.IItemHandler;
import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.lib.internal.inventory.IItemTransactor;
import buildcraft.lib.platform.storage.PlatformStorage;
import buildcraft.lib.platform.storage.StorageAdapters;
import buildcraft.lib.compat.transfer.TransferJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Loader-neutral internal sided lookup backed by Fabric Transfer, not NeoForge.
 * Native registered Fabric storage is preferred; owned BCCE tile capabilities
 * are a fallback for internal tokens (MJ and injectable machine endpoints).
 * No lookup loads a chunk or mutates storage during an open transaction.
 */
public final class CapUtil {
    public static final BlockCapability<IItemHandler, Direction> CAP_ITEMS =
        BlockCapability.createSided(id("items"), IItemHandler.class);
    public static final BlockCapability<IFluidHandler, Direction> CAP_FLUIDS =
        BlockCapability.createSided(id("fluids"), IFluidHandler.class);
    public static final BlockCapability<IItemTransactor, Direction> CAP_ITEM_TRANSACTOR =
        BlockCapability.createSided(id("item_transactor"), IItemTransactor.class);
    public static final EntityCapability<IItemTransactor, Direction> CAP_ITEM_TRANSACTOR_ENTITY =
        EntityCapability.createSided(id("item_transactor"), IItemTransactor.class);
    public static final BlockCapability<IEnergyStorage, Direction> CAP_FE =
        BlockCapability.createSided(id("fe"), IEnergyStorage.class);
    private CapUtil() {}
    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("buildcraftlib", path); }

    @Nullable public static IItemHandler getItemHandler(Level level, BlockPos pos, Direction side) {
        var storage = PlatformStorage.items(level, pos, side);
        if (storage != null) return StorageAdapters.toNativeItems(storage);
        return TransferJournal.active() ? null : owned(level, pos, CAP_ITEMS, side);
    }
    @Nullable public static IFluidHandler getFluidHandler(Level level, BlockPos pos, Direction side) {
        var storage = PlatformStorage.fluids(level, pos, side);
        if (storage != null) return StorageAdapters.fromVolumeFluids(storage);
        return TransferJournal.active() ? null : owned(level, pos, CAP_FLUIDS, side);
    }
    @Nullable public static IEnergyStorage getEnergyStorage(Level level, BlockPos pos, Direction side) {
        var storage = PlatformStorage.energy(level, pos, side);
        if (storage != null) return StorageAdapters.toNativeEnergy(storage);
        return TransferJournal.active() ? null : owned(level, pos, CAP_FE, side);
    }
    @Nullable public static IItemHandler getItemHandler(Entity entity, Direction side) {
        return StorageAdapters.toNativeItems(PlatformStorage.items(entity, side));
    }

    @Nullable
    private static <T> T owned(Level level, BlockPos pos, BlockCapability<T, Direction> cap, Direction side) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return null;
        BlockEntity tile = level.getBlockEntity(pos);
        return tile instanceof IBCCapabilityProvider provider && !tile.isRemoved()
            ? provider.getCapability(cap, side) : null;
    }

    @Nullable @SuppressWarnings("unchecked")
    public static <T, C> T getCapability(Level level, BlockPos pos,
        BlockCapability<T, C> capability, C context) {
        if (level == null || pos == null || capability == null || !level.hasChunkAt(pos)) return null;
        if (context != null && !(context instanceof Direction)) return null;
        Direction side = (Direction) context;
        if (capability == CAP_ITEMS) return (T) getItemHandler(level, pos, side);
        if (capability == CAP_FLUIDS) return (T) getFluidHandler(level, pos, side);
        if (capability == CAP_FE) return (T) getEnergyStorage(level, pos, side);
        return (T) owned(level, pos, (BlockCapability<T, Direction>) capability, side);
    }
    @Nullable @SuppressWarnings("unchecked")
    public static <T, C> T getCapability(Entity entity,
        EntityCapability<T, C> capability, C context) {
        if (entity == null || entity.isRemoved() || capability == null) return null;
        if (capability == CAP_ITEM_TRANSACTOR_ENTITY && entity instanceof net.minecraft.world.Container container) {
            return (T) new buildcraft.lib.inventory.InventoryWrapper(container);
        }
        return null;
    }
}
