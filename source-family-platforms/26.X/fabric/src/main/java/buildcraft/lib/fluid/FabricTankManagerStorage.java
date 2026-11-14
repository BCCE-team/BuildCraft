/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.fluid;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidVolume;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.HolderLookup;

/**
 * Native Fabric Storage view of a BuildCraft tank manager.
 *
 * <p>Slot reads remain in the manager's stable declaration order, while
 * aggregate insertion/extraction preserve NeoForge's distinct fill-only-first
 * and drain-only-first priorities. All child mutations receive the exact
 * parent transaction context, so a multi-tank transfer rolls back as one
 * operation.</p>
 */
public final class FabricTankManagerStorage implements SlottedStorage<FluidVariant> {
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L;

    private final FabricTankManager manager;
    private final HolderLookup.Provider registries;
    private final Map<FabricTankState, FabricTankStorage> slots = new IdentityHashMap<>();

    public FabricTankManagerStorage(FabricTankManager manager, HolderLookup.Provider registries) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.registries = Objects.requireNonNull(registries, "registries");
    }

    private FluidVolume apiVolume(FluidVariant variant, long milliBuckets) {
        if (variant == null || variant.isBlank() || milliBuckets <= 0) return FluidVolume.empty();
        var decoded = FabricFluidVariants.toApi(variant, registries).orElse(null);
        if (decoded == null) return FluidVolume.empty();
        return FabricFluidCompatRegistry.canonicalize(
            FluidVolume.of(decoded, FluidAmount.of(milliBuckets)));
    }

    private FabricTankStorage slotFor(FabricTankState tank) {
        return slots.computeIfAbsent(tank, value -> new FabricTankStorage(value, registries));
    }

    public int getSlotCount() {
        return manager.getTanks();
    }

    public SingleSlotStorage<FluidVariant> getSlot(int slot) {
        if (slot < 0 || slot >= manager.getTanks()) {
            throw new IndexOutOfBoundsException("Tank slot out of range: " + slot);
        }
        return slotFor(manager.get(slot));
    }

    public Iterator<StorageView<FluidVariant>> iterator() {
        return manager.tanks().stream().map(tank -> (StorageView<FluidVariant>) slotFor(tank)).iterator();
    }

    public long insert(FluidVariant variant, long maxAmount, TransactionContext transaction) {
        if (maxAmount < DROPLETS_PER_MB) return 0L;
        FluidVolume offered = apiVolume(variant, maxAmount / DROPLETS_PER_MB);
        if (offered.isEmpty()) return 0L;

        long remaining = offered.amount().milliBuckets();
        long inserted = 0L;
        for (FabricTankState tank : manager.fillOrder()) {
            if (remaining <= 0) break;
            if (!tank.canFill()) continue;
            int moved = tank.fill(offered.withAmount(FluidAmount.of(remaining)), false, transaction);
            inserted += moved;
            remaining -= moved;
        }
        return inserted * DROPLETS_PER_MB;
    }

    public long extract(FluidVariant variant, long maxAmount, TransactionContext transaction) {
        if (maxAmount < DROPLETS_PER_MB) return 0L;
        FluidVolume requested = apiVolume(variant, maxAmount / DROPLETS_PER_MB);
        if (requested.isEmpty()) return 0L;

        long remaining = requested.amount().milliBuckets();
        long extracted = 0L;
        for (FabricTankState tank : manager.drainOrder()) {
            if (remaining <= 0) break;
            if (!tank.canDrain() || !tank.matchesVariant(requested)) continue;
            FluidVolume moved = tank.drain(
                requested.withAmount(FluidAmount.of(remaining)), false, transaction);
            if (moved.isEmpty()) continue;
            long count = moved.amount().milliBuckets();
            extracted += count;
            remaining -= count;
        }
        return extracted * DROPLETS_PER_MB;
    }

    public boolean supportsInsertion() {
        return manager.tanks().stream().anyMatch(tank ->
            tank.canFill() && !tank.hasDeferredUnknownFluid() && tank.getFluidAmount() < tank.getCapacity());
    }

    public boolean supportsExtraction() {
        return manager.tanks().stream().anyMatch(tank ->
            tank.canDrain() && !tank.hasDeferredUnknownFluid() && !tank.getFluid().isEmpty());
    }

    public long getVersion() {
        long version = 0L;
        for (FabricTankState tank : manager.tanks()) version += tank.version();
        return version;
    }
}
