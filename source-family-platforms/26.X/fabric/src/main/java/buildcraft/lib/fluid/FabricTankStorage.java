/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.fluid;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidVolume;
import java.util.Objects;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.base.SingleFluidStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.HolderLookup;

/**
 * Native Fabric Transfer API view over one canonical BCCE tank state.
 *
 * <p>The Fabric transaction is forwarded into the tank's own snapshot journal;
 * this wrapper does not keep a second, competing copy of fluid state. All
 * externally visible quantities are droplets, while the BuildCraft gameplay
 * contract remains whole milliBuckets.</p>
 */
public final class FabricTankStorage extends SingleFluidStorage {
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L;

    private final FabricTankState tank;
    private final HolderLookup.Provider registries;

    public FabricTankStorage(FabricTankState tank, HolderLookup.Provider registries) {
        this.tank = Objects.requireNonNull(tank, "tank");
        this.registries = Objects.requireNonNull(registries, "registries");
    }

    public FabricTankState tankState() {
        return tank;
    }

    private FluidVolume apiVolume(FluidVariant variant, long milliBuckets) {
        if (variant == null || variant.isBlank() || milliBuckets <= 0) return FluidVolume.empty();
        var decoded = FabricFluidVariants.toApi(variant, registries).orElse(null);
        if (decoded == null) return FluidVolume.empty();
        return FabricFluidCompatRegistry.canonicalize(
            FluidVolume.of(decoded, FluidAmount.of(milliBuckets)));
    }

    private FluidVariant nativeVariant(FluidVolume volume) {
        if (volume == null || volume.isEmpty()) return FluidVariant.blank();
        return FabricFluidVariants.fromApi(volume.requireVariant(), registries)
            .orElse(FluidVariant.blank());
    }

    protected long getCapacity(FluidVariant variant) {
        return (long) tank.getCapacity() * DROPLETS_PER_MB;
    }

    protected boolean canInsert(FluidVariant variant) {
        return tank.canFill() && tank.isFluidValid(apiVolume(variant, 1));
    }

    protected boolean canExtract(FluidVariant variant) {
        return tank.canDrain() && tank.matchesVariant(apiVolume(variant, 1));
    }

    public long insert(FluidVariant variant, long maxAmount, TransactionContext transaction) {
        if (variant == null || variant.isBlank() || maxAmount < DROPLETS_PER_MB || !tank.canFill()) return 0L;
        long wholeMb = maxAmount / DROPLETS_PER_MB;
        FluidVolume offered = apiVolume(variant, wholeMb);
        if (offered.isEmpty() || !tank.isFluidValid(offered)) return 0L;
        int accepted = tank.fill(offered, false, transaction);
        return (long) accepted * DROPLETS_PER_MB;
    }

    public long extract(FluidVariant variant, long maxAmount, TransactionContext transaction) {
        if (variant == null || variant.isBlank() || maxAmount < DROPLETS_PER_MB || !tank.canDrain()) return 0L;
        long wholeMb = maxAmount / DROPLETS_PER_MB;
        FluidVolume requested = apiVolume(variant, wholeMb);
        if (requested.isEmpty() || !tank.matchesVariant(requested)) return 0L;
        FluidVolume extracted = tank.drain(requested, false, transaction);
        return extracted.isEmpty() ? 0L : extracted.amount().milliBuckets() * DROPLETS_PER_MB;
    }

    public FluidVariant getResource() {
        return nativeVariant(tank.getFluid());
    }

    public long getAmount() {
        return tank.getFluid().amount().milliBuckets() * DROPLETS_PER_MB;
    }

    public long getCapacity() {
        return (long) tank.getCapacity() * DROPLETS_PER_MB;
    }

    public boolean isResourceBlank() {
        return tank.getFluid().isEmpty();
    }

    public boolean supportsInsertion() {
        return tank.canFill() && !tank.hasDeferredUnknownFluid() && tank.getFluidAmount() < tank.getCapacity();
    }

    public boolean supportsExtraction() {
        return tank.canDrain() && !tank.hasDeferredUnknownFluid() && !tank.getFluid().isEmpty();
    }

    public long getVersion() {
        return tank.version();
    }
}
