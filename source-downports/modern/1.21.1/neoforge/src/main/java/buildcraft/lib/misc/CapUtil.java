/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.misc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.internal.inventory.IItemTransactor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/** Common NeoForge capabilities used by BuildCraft. */
public final class CapUtil {
    @Nonnull
    public static final BlockCapability<IItemHandler, Direction> CAP_ITEMS = Capabilities.ItemHandler.BLOCK;

    @Nonnull
    public static final BlockCapability<IFluidHandler, Direction> CAP_FLUIDS = Capabilities.FluidHandler.BLOCK;

    @Nonnull
    public static final BlockCapability<IItemTransactor, Direction> CAP_ITEM_TRANSACTOR =
        BlockCapability.createSided(id("item_transactor"), IItemTransactor.class);

    @Nonnull
    public static final EntityCapability<IItemTransactor, Direction> CAP_ITEM_TRANSACTOR_ENTITY =
        EntityCapability.createSided(id("item_transactor"), IItemTransactor.class);

    @Nonnull
    public static final BlockCapability<IEnergyStorage, Direction> CAP_FE = Capabilities.EnergyStorage.BLOCK;

    private CapUtil() {
    }

    /**
     * Looks up an automation item handler across the NeoForge 1.21.9 transfer API transition.
     *
     * <p>1.21.9+ moved the standard block capability from {@code IItemHandler} to
     * {@code ResourceHandler<ItemResource>}. BuildCraft still uses the legacy interface internally,
     * so adapt the modern handler at the boundary and retain the legacy lookup as a compatibility fallback.</p>
     */
    @Nullable
    public static IItemHandler getItemHandler(@Nullable Level level, @Nullable BlockPos pos, @Nullable Direction side) {
        if (level == null || pos == null) {
            return null;
        }
        return level.getCapability(CAP_ITEMS, pos, side);
    }

    /** Same bridge as {@link #getItemHandler(Level, BlockPos, Direction)}, for fluids. */
    @Nullable
    public static IFluidHandler getFluidHandler(@Nullable Level level, @Nullable BlockPos pos, @Nullable Direction side) {
        if (level == null || pos == null) {
            return null;
        }
        return level.getCapability(CAP_FLUIDS, pos, side);
    }

    /** Same bridge as {@link #getItemHandler(Level, BlockPos, Direction)}, for FE. */
    @Nullable
    public static IEnergyStorage getEnergyStorage(@Nullable Level level, @Nullable BlockPos pos, @Nullable Direction side) {
        if (level == null || pos == null) {
            return null;
        }
        return level.getCapability(CAP_FE, pos, side);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("buildcraftlib", path);
    }

    @Nullable
    public static <T, C> T getCapability(
        @Nullable Level level,
        @Nullable BlockPos pos,
        @Nullable BlockCapability<T, C> capability,
        @Nullable C context
    ) {
        if (level == null || pos == null || capability == null) {
            return null;
        }
        return level.getCapability(capability, pos, context);
    }

    @Nullable
    public static <T, C> T getCapability(
        @Nullable Entity entity,
        @Nullable EntityCapability<T, C> capability,
        @Nullable C context
    ) {
        if (entity == null || capability == null) {
            return null;
        }
        return entity.getCapability(capability, context);
    }
}
