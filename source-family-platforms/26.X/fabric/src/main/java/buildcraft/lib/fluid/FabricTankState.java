/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.fluid;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.compat.transfer.TransferJournal;
import buildcraft.lib.platform.storage.FilteredFluidStorage;
import buildcraft.lib.platform.storage.PlatformFluidPipeTransfer;
import com.mojang.serialization.DynamicOps;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

/**
 * Loader-independent value semantics for a BCCE 26.3 Fabric fluid tank.
 *
 * <p>Provides the 1-tank fill/drain/validation/capacity contract of NeoForge
 * `Tank` without depending on the NeoForge FluidStack or capability types.
 * The owning tile supplies its dirty/sync callback. Native Fabric storage
 * exposure and GUI item-container interactions are separate loader boundaries
 * and must be wired by the future TileBC_Neptune port.</p>
 *
 * <p>Contents are immutable API2 FluidVolumes. A Fabric transaction records
 * a snapshot before every mutation, restores it upon abort and emits exactly
 * one change notification after the outermost commit. Unknown or undecodable
 * nonempty NBT is retained losslessly for later recovery, not overwritten by
 * an empty fluid stack on the next save.</p>
 */
public final class FabricTankState implements FilteredFluidStorage<FluidVolume> {
    private final String name;
    private final Runnable onChanged;
    private final UnaryOperator<FluidVolume> canonicalizer;
    private final TransferJournal<Snapshot> journal;
    private Predicate<FluidVolume> validator;

    private int capacity;
    private long version;
    private FluidVolume contents = FluidVolume.empty();
    @Nullable private CompoundTag deferredNbt;
    private boolean canFill = true;
    private boolean canDrain = true;

    private record Snapshot(FluidVolume fluid, @Nullable CompoundTag deferred, int capacity) {}

    public FabricTankState(String name, int capacity, @Nullable Predicate<FluidVolume> validator,
                           Runnable onChanged) {
        this(name, capacity, validator, onChanged, FabricFluidCompatRegistry::canonicalize);
    }

    public FabricTankState(String name, int capacity, @Nullable Predicate<FluidVolume> validator,
                           Runnable onChanged, UnaryOperator<FluidVolume> canonicalizer) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Tank name is required");
        if (capacity <= 0) throw new IllegalArgumentException("Tank capacity must be positive: " + capacity);
        this.name = name;
        this.capacity = capacity;
        this.validator = validator == null ? value -> true : validator;
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
        this.journal = new TransferJournal<>(
            this::snapshot,
            this::restore,
            oldState -> {
                version++;
                onChanged.run();
            }
        );
    }

    private Snapshot snapshot() {
        return new Snapshot(contents, deferredNbt == null ? null : deferredNbt.copy(), capacity);
    }

    private void restore(Snapshot value) {
        contents = value.fluid();
        capacity = value.capacity();
        deferredNbt = value.deferred() == null ? null : value.deferred().copy();
    }

    private boolean record(@Nullable TransactionContext transaction) {
        return transaction == null ? journal.recordCurrent() : journal.record(transaction);
    }

    private void changed(boolean transactional) {
        if (!transactional) {
            version++;
            onChanged.run();
        }
    }

    private FluidVolume normalized(FluidVolume offered) {
        if (offered == null || offered.isEmpty()) return FluidVolume.empty();
        FluidVolume canonical = canonicalizer.apply(offered);
        return canonical == null ? FluidVolume.empty() : canonical;
    }

    public String getTankName() { return name; }
    public FluidVolume getFluid() { return contents; }
    public int getFluidAmount() { return (int) contents.amount().milliBuckets(); }
    public int getCapacity() { return capacity; }
    public long version() { return version; }
    public boolean isEmpty() { return contents.isEmpty() && deferredNbt == null; }
    public boolean isFull() { return !contents.isEmpty() && getFluidAmount() >= capacity; }
    public boolean canFill() { return canFill; }
    public boolean canDrain() { return canDrain; }
    public void setCanFill(boolean enabled) { canFill = enabled; }
    public void setCanDrain(boolean enabled) { canDrain = enabled; }
    public void setFilter(@Nullable Predicate<FluidVolume> filter) {
        validator = filter == null ? value -> true : filter;
    }

    public boolean hasDeferredUnknownFluid() { return deferredNbt != null; }

    /**
     * Same admission order as the NeoForge tank: a validated, nonempty
     * canonical fluid may fill an empty tank or exactly the same variant.
     * An unresolved saved fluid blocks insertion to prevent data loss.
     */
    /** Compares against the tank's canonical equivalence group and component payload. */
    public boolean matchesVariant(FluidVolume offered) {
        FluidVolume incoming = normalized(offered);
        return !incoming.isEmpty() && !contents.isEmpty()
            && FabricFluidCompatRegistry.areEquivalent(contents, incoming);
    }

    public boolean isFluidValid(FluidVolume offered) {
        FluidVolume incoming = normalized(offered);
        return !incoming.isEmpty() && deferredNbt == null
            && validator.test(incoming)
            && (contents.isEmpty() || FabricFluidCompatRegistry.areEquivalent(contents, incoming));
    }

    public int getTanks() { return 1; }
    public FluidVolume getFluidInTank(int tank) {
        return tank == 0 ? contents : FluidVolume.empty();
    }
    public int getTankCapacity(int tank) { return tank == 0 ? capacity : 0; }
    public boolean isFluidValid(int tank, FluidVolume offered) {
        return tank == 0 && isFluidValid(offered);
    }

    public int fill(FluidVolume offered, boolean simulate) {
        return fill(offered, simulate, null);
    }

    /** Explicit transaction-context overload used by the native Fabric Transfer storage adapter. */
    public int fill(FluidVolume offered, boolean simulate, @Nullable TransactionContext transaction) {
        if (!canFill || deferredNbt != null) return 0;
        FluidVolume incoming = normalized(offered);
        if (!isFluidValid(incoming)) return 0;
        long available = Math.max(0L, (long) capacity - contents.amount().milliBuckets());
        int accepted = (int) Math.min(available, incoming.amount().milliBuckets());
        if (accepted <= 0) return 0;
        if (!simulate) {
            boolean transactional = record(transaction);
            contents = contents.isEmpty()
                ? incoming.withAmount(FluidAmount.of(accepted))
                : contents.withAmount(contents.amount().plus(FluidAmount.of(accepted)));
            changed(transactional);
        }
        return accepted;
    }

    public FluidVolume drain(FluidVolume requested, boolean simulate) {
        return drain(requested, simulate, null);
    }

    /** Explicit transaction-context overload used by the native Fabric Transfer storage adapter. */
    public FluidVolume drain(
        FluidVolume requested, boolean simulate, @Nullable TransactionContext transaction
    ) {
        if (requested == null || requested.isEmpty() || contents.isEmpty()) return FluidVolume.empty();
        FluidVolume incoming = normalized(requested);
        if (!matchesVariant(incoming)) return FluidVolume.empty();
        return drainBounded(incoming.amount().milliBuckets(), simulate, transaction);
    }

    public FluidVolume drain(int amount, boolean simulate) {
        return drain(amount, simulate, null);
    }

    public FluidVolume drain(int amount, boolean simulate, @Nullable TransactionContext transaction) {
        return drainBounded(amount, simulate, transaction);
    }

    public FluidVolume drain(Predicate<FluidVolume> filter, int amount, boolean simulate) {
        return drain(filter, amount, simulate, null);
    }

    public FluidVolume drain(
        Predicate<FluidVolume> filter, int amount, boolean simulate,
        @Nullable TransactionContext transaction
    ) {
        if (filter == null || contents.isEmpty() || !filter.test(contents)) return FluidVolume.empty();
        return drainBounded(amount, simulate, transaction);
    }

    private FluidVolume drainBounded(
        long amount, boolean simulate, @Nullable TransactionContext transaction
    ) {
        if (!canDrain || deferredNbt != null || contents.isEmpty() || amount <= 0) {
            return FluidVolume.empty();
        }
        long count = Math.min(amount, contents.amount().milliBuckets());
        FluidVolume taken = contents.withAmount(FluidAmount.of(count));
        if (!simulate) {
            boolean transactional = record(transaction);
            long left = contents.amount().milliBuckets() - count;
            contents = left <= 0 ? FluidVolume.empty() : contents.withAmount(FluidAmount.of(left));
            changed(transactional);
        }
        return taken;
    }

    /** Used by internal machine state restoration, not a public fill operation. */
    public void setFluid(FluidVolume newFluid) {
        FluidVolume incoming = normalized(newFluid);
        if (!incoming.isEmpty() && incoming.amount().milliBuckets() > capacity) {
            incoming = incoming.withAmount(FluidAmount.of(capacity));
        }
        if (contents.equals(incoming) && deferredNbt == null) return;
        boolean transactional = record(null);
        contents = incoming;
        deferredNbt = null;
        changed(transactional);
    }

    public void setCapacity(int newCapacity) {
        if (newCapacity <= 0) {
            throw new IllegalArgumentException("Tank capacity must be positive: " + newCapacity);
        }
        boolean transactional = record(null);
        capacity = newCapacity;
        if (contents.amount().milliBuckets() > capacity) {
            contents = contents.withAmount(FluidAmount.of(capacity));
        }
        changed(transactional);
    }

    /**
     * NeoForge-compatible persisted shape: id, amount and the same
     * DataComponentPatch compound used by FluidStack. No Fabric-only
     * opaque encoding leaks into save data.
     */
    public CompoundTag writeToNBT(HolderLookup.Provider registries) {
        Objects.requireNonNull(registries, "registries");
        if (deferredNbt != null) return deferredNbt.copy();
        CompoundTag result = new CompoundTag();
        if (contents.isEmpty()) return result;

        var nativeVariant = FabricFluidVariants.fromApi(contents.requireVariant(), registries)
            .orElseThrow(() -> new IllegalStateException("Unrepresentable persisted fluid components in " + name));
        result.putString("id", contents.requireVariant().fluidId().toString());
        result.putInt("amount", getFluidAmount());
        DataComponentPatch components = nativeVariant.getComponentsPatch();
        if (components != null && !components.isEmpty()) {
            DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
            Tag encoded = DataComponentPatch.CODEC.encodeStart(ops, components).getOrThrow();
            if (!(encoded instanceof CompoundTag compound)) {
                throw new IllegalStateException("Fluid components did not encode as CompoundTag in tank " + name);
            }
            result.put("components", compound);
        }
        return result;
    }

    /** Accepts NeoForge's modern or Forge's historical fluid NBT. */
    public void readFromNBT(@Nullable CompoundTag saved, HolderLookup.Provider registries) {
        Objects.requireNonNull(registries, "registries");
        if (saved == null || saved.isEmpty()) {
            contents = FluidVolume.empty();
            deferredNbt = null;
            return;
        }
        FluidVolume decoded = PlatformFluidPipeTransfer.readLegacyNbt(saved, registries);
        if (!decoded.isEmpty() && validator.test(normalized(decoded))) {
            FluidVolume fluid = normalized(decoded);
            contents = fluid.amount().milliBuckets() > capacity
                ? fluid.withAmount(FluidAmount.of(capacity)) : fluid;
            deferredNbt = null;
            return;
        }
        // Preserve undecodable NBT (unknown fluid registry or components)
        // through future saves until its provider is reinstalled.
        // Keep every undecodable nonempty payload, even when its fluid ID is
        // malformed or absent. A later save must never erase unknown source data.
        contents = FluidVolume.empty();
        deferredNbt = saved.copy();
    }

    public String toString() {
        return "FabricTankState[" + name + ", " + getFluidAmount() + "/" + capacity
            + " mB" + (deferredNbt == null ? "" : ", deferred unknown NBT") + "]";
    }
}
