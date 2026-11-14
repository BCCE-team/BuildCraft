/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.fluid;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.drop.FluidDropContext;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.compat.NbtCompat;
import buildcraft.lib.platform.storage.FilteredFluidStorage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * Minecraft 26.3 Fabric counterpart to NeoForge TankManager's storage logic.
 * The fill-only-first / drain-only-first tank selection order is intentional
 * BuildCraft gameplay behavior and must not be replaced with a generic list
 * traversal. All amounts are whole mB and variants retain Data Components.
 *
 * <p>GUI bucket interaction and network cache synchronization are deliberately
 * outside this state layer; the owning native tile adapter wires them.</p>
 */
public final class FabricTankManager implements FilteredFluidStorage<FluidVolume> {
    private final ArrayList<FabricTankState> tanks = new ArrayList<>();
    private FabricTankManagerStorage nativeStorage;
    private HolderLookup.Provider nativeRegistries;

    public FabricTankManager() {}

    public FabricTankManager(FabricTankState... initialTanks) {
        addAll(Arrays.asList(initialTanks));
    }

    public void add(FabricTankState tank) { tanks.add(Objects.requireNonNull(tank, "tank")); }
    public void addAll(Collection<? extends FabricTankState> newTanks) {
        for (FabricTankState tank : newTanks) add(tank);
    }
    public FabricTankState get(int index) { return tanks.get(index); }
    public List<FabricTankState> tanks() { return List.copyOf(tanks); }
    public int size() { return tanks.size(); }

    /** Stable Fabric Transfer view for this manager and its current world registries. */
    public synchronized FabricTankManagerStorage asFabricStorage(HolderLookup.Provider registries) {
        Objects.requireNonNull(registries, "registries");
        if (nativeStorage == null || nativeRegistries != registries) {
            nativeRegistries = registries;
            nativeStorage = new FabricTankManagerStorage(this, registries);
        }
        return nativeStorage;
    }

    List<FabricTankState> fillOrder() {
        List<FabricTankState> order = new ArrayList<>();
        for (FabricTankState tank : tanks) if (tank.canFill() && !tank.canDrain()) order.add(tank);
        for (FabricTankState tank : tanks) if (tank.canFill() && tank.canDrain()) order.add(tank);
        return order;
    }

    List<FabricTankState> drainOrder() {
        List<FabricTankState> order = new ArrayList<>();
        for (FabricTankState tank : tanks) if (!tank.canFill() && tank.canDrain()) order.add(tank);
        for (FabricTankState tank : tanks) if (tank.canFill() && tank.canDrain()) order.add(tank);
        return order;
    }

    public int getTanks() { return tanks.size(); }
    public FluidVolume getFluidInTank(int tank) {
        return tank < 0 || tank >= tanks.size() ? FluidVolume.empty() : tanks.get(tank).getFluid();
    }
    public int getTankCapacity(int tank) {
        return tank < 0 || tank >= tanks.size() ? 0 : tanks.get(tank).getCapacity();
    }
    public boolean isFluidValid(int tank, FluidVolume fluid) {
        return tank >= 0 && tank < tanks.size() && tanks.get(tank).isFluidValid(fluid);
    }

    public int fillTank(int tank, FluidVolume fluid, boolean simulate) {
        return tank < 0 || tank >= tanks.size() ? 0 : tanks.get(tank).fill(fluid, simulate);
    }

    public FluidVolume drainTank(int tank, FluidVolume fluid, boolean simulate) {
        return tank < 0 || tank >= tanks.size() ? FluidVolume.empty()
            : tanks.get(tank).drain(fluid, simulate);
    }

    public int fill(FluidVolume offered, boolean simulate) {
        if (offered == null || offered.isEmpty()) return 0;
        // Internal FluidStorage.fill reports int mB. Never move more than can
        // be reported, or a large API2 FluidVolume would silently overflow.
        long left = Math.min(offered.amount().milliBuckets(), Integer.MAX_VALUE);
        long total = 0;
        for (FabricTankState tank : fillOrder()) {
            if (left <= 0) break;
            int accepted = tank.fill(offered.withAmount(FluidAmount.of(left)), simulate);
            total += accepted;
            left -= accepted;
        }
        return (int) total; // caller-visible requested amount is bounded by tank capacities
    }

    public FluidVolume drain(FluidVolume requested, boolean simulate) {
        if (requested == null || requested.isEmpty()) return FluidVolume.empty();
        long left = requested.amount().milliBuckets();
        long total = 0;
        FluidVolume chosen = FluidVolume.empty();
        for (FabricTankState tank : drainOrder()) {
            if (left <= 0) break;
            FluidVolume fluid = tank.getFluid();
            if (fluid.isEmpty() || !tank.matchesVariant(requested)) continue;
            FluidVolume moved = tank.drain((int) Math.min(left, Integer.MAX_VALUE), simulate);
            if (moved.isEmpty() || !FabricFluidCompatRegistry.areEquivalent(moved, requested)) continue;
            if (chosen.isEmpty()) chosen = moved;
            total += moved.amount().milliBuckets();
            left -= moved.amount().milliBuckets();
        }
        return chosen.isEmpty() ? FluidVolume.empty()
            : chosen.withAmount(FluidAmount.of(total));
    }

    public FluidVolume drain(int amount, boolean simulate) {
        return drainMatching(fluid -> true, amount, simulate);
    }

    public FluidVolume drain(Predicate<FluidVolume> filter, int amount, boolean simulate) {
        return filter == null ? FluidVolume.empty() : drainMatching(filter, amount, simulate);
    }

    private FluidVolume drainMatching(Predicate<FluidVolume> filter, int amount, boolean simulate) {
        if (amount <= 0) return FluidVolume.empty();
        long left = amount;
        long total = 0;
        FluidVolume chosen = FluidVolume.empty();
        for (FabricTankState tank : drainOrder()) {
            if (left <= 0) break;
            FluidVolume current = tank.getFluid();
            if (current.isEmpty() || !filter.test(current)) continue;
            if (!chosen.isEmpty() && !FabricFluidCompatRegistry.areEquivalent(chosen, current)) continue;
            FluidVolume moved = tank.drain((int) Math.min(left, Integer.MAX_VALUE), simulate);
            if (moved.isEmpty()) continue;
            if (chosen.isEmpty()) chosen = moved;
            total += moved.amount().milliBuckets();
            left -= moved.amount().milliBuckets();
        }
        return chosen.isEmpty() ? FluidVolume.empty()
            : chosen.withAmount(FluidAmount.of(total));
    }

    /** Keeps the NeoForge `{tankName: {id,amount,components}}` saved-data shape. */
    public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        CompoundTag nbt = new CompoundTag();
        for (FabricTankState tank : tanks) {
            nbt.put(tank.getTankName(), tank.writeToNBT(registries));
        }
        return nbt;
    }

    public void deserializeNBT(HolderLookup.Provider registries, CompoundTag nbt) {
        for (FabricTankState tank : tanks) {
            tank.readFromNBT(NbtCompat.getCompound(nbt, tank.getTankName()), registries);
        }
    }

    /** Same API2 drop service used by NeoForge TankManager/FluidDropRuntime. */
    public void addDrops(NonNullList<ItemStack> toDrop) {
        if (toDrop == null) return;
        for (FabricTankState tank : tanks) {
            FluidVolume fluid = tank.getFluid();
            if (fluid.isEmpty()) continue;
            for (ItemStack drop : BuildCraftApi.service(BuildCraftServices.FLUID_DROPS)
                .createDrops(FluidDropContext.of(fluid))) {
                if (drop != null && !drop.isEmpty()) toDrop.add(drop.copy());
            }
        }
    }
}
