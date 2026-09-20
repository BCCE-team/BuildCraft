/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.cap;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.platform.storage.EnergyStorage;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.lib.platform.storage.ItemStorage;
import buildcraft.lib.platform.storage.StorageAdapters;
import buildcraft.lib.platform.storage.StorageMap;

import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.common.util.NonNullSupplier;

/** Provides a simple way of mapping {@link Capability}'s to instances. Also allows for additional providers. */
public class CapabilityHelper implements ICapabilityProvider {
    private final Map<EnumPipePart, Map<Capability<?>, NonNullSupplier<?>>> caps = new EnumMap<>(EnumPipePart.class);
    private final Map<EnumPipePart, Map<Capability<?>, LazyOptional<?>>> cached = new EnumMap<>(EnumPipePart.class);
    private final List<ICapabilityProvider> additional = new ArrayList<>();
    private final StorageMap storages = new StorageMap();
    private boolean valid = true;

    public CapabilityHelper() {
        for (EnumPipePart face : EnumPipePart.VALUES) {
            caps.put(face, new HashMap<>());
            cached.put(face, new HashMap<>());
        }
    }

    private Map<Capability<?>, NonNullSupplier<?>> getCapMap(@Nullable Direction facing) {
        return caps.get(EnumPipePart.fromFacing(facing));
    }

    private Map<Capability<?>, LazyOptional<?>> getCache(@Nullable Direction facing) {
        return cached.get(EnumPipePart.fromFacing(facing));
    }

    @SuppressWarnings("unchecked")
    private <T> LazyOptional<T> cached(
        @Nonnull Capability<T> capability,
        @Nullable Direction facing,
        NonNullSupplier<? extends T> supplier
    ) {
        if (!valid) {
            return LazyOptional.empty();
        }
        return (LazyOptional<T>) getCache(facing).computeIfAbsent(
            capability,
            ignored -> LazyOptional.of(supplier::get)
        );
    }

    private void invalidateCached(@Nonnull Capability<?> capability, EnumPipePart... parts) {
        for (EnumPipePart part : parts) {
            LazyOptional<?> old = cached.get(part).remove(capability);
            if (old != null) {
                old.invalidate();
            }
        }
    }

    public <T> void addCapabilityInstance(@Nullable Capability<T> cap, T instance, EnumPipePart... parts) {
        addCapability(cap, () -> instance, parts);
    }

    public <T> void addCapability(@Nullable Capability<T> cap, NonNullSupplier<T> getter, EnumPipePart... parts) {
        if (cap == null) {
            return;
        }
        invalidateCached(cap, parts);
        for (EnumPipePart part : parts) {
            caps.get(part).put(cap, getter);
        }
    }

    public <T> void addCapability(@Nullable Capability<T> cap, Function<Direction, T> getter, EnumPipePart... parts) {
        if (cap == null) {
            return;
        }
        invalidateCached(cap, parts);
        for (EnumPipePart part : parts) {
            caps.get(part).put(cap, () -> getter.apply(part.face));
        }
    }

    public <T extends ICapabilityProvider> T addProvider(T provider) {
        if (provider != null) {
            additional.add(provider);
        }
        return provider;
    }

    public void addItemStorage(ItemStorage storage, EnumPipePart... parts) {
        invalidateCached(CapUtil.CAP_ITEMS, parts);
        storages.addItems(storage, parts);
    }

    public void addItemStorage(Function<Direction, ? extends ItemStorage> storage, EnumPipePart... parts) {
        invalidateCached(CapUtil.CAP_ITEMS, parts);
        storages.addItems(storage, parts);
    }

    public void addFluidStorage(FluidStorage<?> storage, EnumPipePart... parts) {
        invalidateCached(CapUtil.CAP_FLUIDS, parts);
        storages.addFluids(storage, parts);
    }

    public void addFluidStorage(Function<Direction, ? extends FluidStorage<?>> storage, EnumPipePart... parts) {
        invalidateCached(CapUtil.CAP_FLUIDS, parts);
        storages.addFluids(storage, parts);
    }

    public void addEnergyStorage(EnergyStorage storage, EnumPipePart... parts) {
        invalidateCached(CapUtil.CAP_FE, parts);
        storages.addEnergy(storage, parts);
    }

    public void addEnergyStorage(Function<Direction, ? extends EnergyStorage> storage, EnumPipePart... parts) {
        invalidateCached(CapUtil.CAP_FE, parts);
        storages.addEnergy(storage, parts);
    }

    @SuppressWarnings("unchecked")
    private static net.minecraftforge.fluids.capability.IFluidHandler nativeFluids(FluidStorage<?> storage) {
        return StorageAdapters.toNativeFluids((FluidStorage<net.minecraftforge.fluids.FluidStack>) storage);
    }

    /** Invalidates every handle owned by this helper. Delegated providers keep ownership of their own handles. */
    public void invalidate() {
        valid = false;
        for (Map<Capability<?>, LazyOptional<?>> byCapability : cached.values()) {
            for (LazyOptional<?> optional : byCapability.values()) {
                optional.invalidate();
            }
            byCapability.clear();
        }
    }

    /** Allows fresh handles to be created after Forge revives the owning block entity. */
    public void revive() {
        valid = true;
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@Nonnull Capability<T> capability, @Nullable Direction facing) {
        if (!valid) {
            return LazyOptional.empty();
        }
        if (capability == CapUtil.CAP_ITEMS) {
            ItemStorage storage = storages.items(facing);
            if (storage != null) {
                return cached(capability, facing, () -> (T) StorageAdapters.toNativeItems(storage));
            }
        }
        if (capability == CapUtil.CAP_FLUIDS) {
            FluidStorage<?> storage = storages.fluids(facing);
            if (storage != null) {
                return cached(capability, facing, () -> (T) nativeFluids(storage));
            }
        }
        if (capability == CapUtil.CAP_FE) {
            EnergyStorage storage = storages.energy(facing);
            if (storage != null) {
                return cached(capability, facing, () -> (T) StorageAdapters.toNativeEnergy(storage));
            }
        }
        NonNullSupplier<?> supplier = getCapMap(facing).get(capability);
        if (supplier != null) {
            return cached(capability, facing, () -> (T) supplier.get());
        }
        for (ICapabilityProvider provider : additional) {
            LazyOptional<T> value = provider.getCapability(capability, facing);
            if (value.isPresent()) {
                return value;
            }
        }
        return LazyOptional.empty();
    }
}
