//? source if >=1.21.1
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
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Common NeoForge capabilities used by BuildCraft. */
public final class CapUtil {
    @Nonnull
    public static final BlockCapability<IItemHandler, Direction> CAP_ITEMS = BlockCapability.createSided(id("items"), IItemHandler.class);

    @Nonnull
    public static final BlockCapability<IFluidHandler, Direction> CAP_FLUIDS = BlockCapability.createSided(id("fluids"), IFluidHandler.class);

    @Nonnull
    public static final BlockCapability<IItemTransactor, Direction> CAP_ITEM_TRANSACTOR =
        BlockCapability.createSided(id("item_transactor"), IItemTransactor.class);

    @Nonnull
    public static final EntityCapability<IItemTransactor, Direction> CAP_ITEM_TRANSACTOR_ENTITY =
        EntityCapability.createSided(id("item_transactor"), IItemTransactor.class);

    @Nonnull
    public static final BlockCapability<IEnergyStorage, Direction> CAP_FE = BlockCapability.createSided(id("fe"), IEnergyStorage.class);

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
        ResourceHandler<ItemResource> modern = level.getCapability(Capabilities.Item.BLOCK, pos, side);
        if (modern != null) {
            return buildcraft.lib.compat.transfer.TransferInterop.importItems(modern);
        }
        return buildcraft.lib.compat.transfer.TransferJournal.active() ? null : level.getCapability(CAP_ITEMS, pos, side);
    }

    /** Same bridge as {@link #getItemHandler(Level, BlockPos, Direction)}, for fluids. */
    @Nullable
    public static IFluidHandler getFluidHandler(@Nullable Level level, @Nullable BlockPos pos, @Nullable Direction side) {
        if (level == null || pos == null) {
            return null;
        }
        ResourceHandler<FluidResource> modern = level.getCapability(Capabilities.Fluid.BLOCK, pos, side);
        if (modern != null) {
            return buildcraft.lib.compat.transfer.TransferInterop.importFluids(modern);
        }
        return buildcraft.lib.compat.transfer.TransferJournal.active() ? null : level.getCapability(CAP_FLUIDS, pos, side);
    }

    /** Same bridge as {@link #getItemHandler(Level, BlockPos, Direction)}, for FE. */
    @Nullable
    public static IEnergyStorage getEnergyStorage(@Nullable Level level, @Nullable BlockPos pos, @Nullable Direction side) {
        if (level == null || pos == null) {
            return null;
        }
        EnergyHandler modern = level.getCapability(Capabilities.Energy.BLOCK, pos, side);
        if (modern != null) {
            return buildcraft.lib.compat.transfer.TransferInterop.importEnergy(modern);
        }
        return buildcraft.lib.compat.transfer.TransferJournal.active() ? null : level.getCapability(CAP_FE, pos, side);
    }

    /** Entity automation honors the caller's side, including modern minecart/boat/modded inventories. */
    @Nullable
    public static IItemHandler getItemHandler(@Nullable Entity entity, @Nullable Direction side) {
        if (entity == null || entity.isRemoved()) return null;
        ResourceHandler<ItemResource> modern = entity.getCapability(Capabilities.Item.ENTITY_AUTOMATION, side);
        if (modern == null) modern = entity.getCapability(Capabilities.Item.ENTITY);
        return modern == null ? null : buildcraft.lib.compat.transfer.TransferInterop.importItems(modern);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("buildcraftlib", path);
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
        if (context == null || context instanceof Direction) {
            Direction side = (Direction) context;
            if (capability == CAP_ITEMS) return (T) getItemHandler(level, pos, side);
            if (capability == CAP_FLUIDS) return (T) getFluidHandler(level, pos, side);
            if (capability == CAP_FE) return (T) getEnergyStorage(level, pos, side);
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
