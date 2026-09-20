/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.cap;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.platform.storage.EnergyStorage;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.lib.platform.storage.ItemStorage;
import buildcraft.lib.platform.storage.StorageAdapters;
import buildcraft.lib.platform.storage.StorageMap;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;

/** Maps block capabilities to instances and delegates unresolved lookups to additional providers. */
public class CapabilityHelper implements IBCCapabilityProvider {
    private final Map<EnumPipePart, Map<BlockCapability<?, Direction>, Supplier<?>>> caps =
        new EnumMap<>(EnumPipePart.class);
    private final List<IBCCapabilityProvider> additional = new ArrayList<>();
    private final StorageMap storages = new StorageMap();

    public CapabilityHelper() {
        for (EnumPipePart face : EnumPipePart.VALUES) {
            caps.put(face, new HashMap<>());
        }
    }

    private Map<BlockCapability<?, Direction>, Supplier<?>> getCapMap(@Nullable Direction facing) {
        return caps.get(EnumPipePart.fromFacing(facing));
    }

    public <T> void addCapabilityInstance(
        @Nullable BlockCapability<T, Direction> capability,
        T instance,
        EnumPipePart... parts
    ) {
        addCapability(capability, () -> instance, parts);
    }

    public <T> void addCapability(
        @Nullable BlockCapability<T, Direction> capability,
        Supplier<T> getter,
        EnumPipePart... parts
    ) {
        if (capability == null) {
            return;
        }
        for (EnumPipePart part : parts) {
            caps.get(part).put(capability, getter);
        }
    }

    public <T> void addCapability(
        @Nullable BlockCapability<T, Direction> capability,
        Function<Direction, T> getter,
        EnumPipePart... parts
    ) {
        if (capability == null) {
            return;
        }
        for (EnumPipePart part : parts) {
            caps.get(part).put(capability, () -> getter.apply(part.face));
        }
    }

    public <T extends IBCCapabilityProvider> T addProvider(T provider) {
        if (provider != null) {
            additional.add(provider);
        }
        return provider;
    }

    public void addItemStorage(ItemStorage storage, EnumPipePart... parts) {
        storages.addItems(storage, parts);
    }

    public void addItemStorage(Function<Direction, ? extends ItemStorage> storage, EnumPipePart... parts) {
        storages.addItems(storage, parts);
    }

    public void addFluidStorage(FluidStorage<?> storage, EnumPipePart... parts) {
        storages.addFluids(storage, parts);
    }

    public void addFluidStorage(Function<Direction, ? extends FluidStorage<?>> storage, EnumPipePart... parts) {
        storages.addFluids(storage, parts);
    }

    public void addEnergyStorage(EnergyStorage storage, EnumPipePart... parts) {
        storages.addEnergy(storage, parts);
    }

    public void addEnergyStorage(Function<Direction, ? extends EnergyStorage> storage, EnumPipePart... parts) {
        storages.addEnergy(storage, parts);
    }

    @SuppressWarnings("unchecked")
    private static net.neoforged.neoforge.fluids.capability.IFluidHandler nativeFluids(FluidStorage<?> storage) {
        return StorageAdapters.toNativeFluids((FluidStorage<net.neoforged.neoforge.fluids.FluidStack>) storage);
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T getCapability(BlockCapability<T, Direction> capability, @Nullable Direction facing) {
        if (capability == CapUtil.CAP_ITEMS) {
            ItemStorage storage = storages.items(facing);
            if (storage != null) return (T) StorageAdapters.toNativeItems(storage);
        }
        if (capability == CapUtil.CAP_FLUIDS) {
            FluidStorage<?> storage = storages.fluids(facing);
            if (storage != null) return (T) nativeFluids(storage);
        }
        if (capability == CapUtil.CAP_FE) {
            EnergyStorage storage = storages.energy(facing);
            if (storage != null) return (T) StorageAdapters.toNativeEnergy(storage);
        }
        Supplier<?> supplier = getCapMap(facing).get(capability);
        if (supplier != null) {
            return (T) supplier.get();
        }
        for (IBCCapabilityProvider provider : additional) {
            T value = provider.getCapability(capability, facing);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
