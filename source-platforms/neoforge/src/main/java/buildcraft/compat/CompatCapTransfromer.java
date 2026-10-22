package buildcraft.compat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import buildcraft.lib.misc.CapUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;
//? if mc_26_x && (>=26.3) {
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
//? } else {
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
//?}

/**
 * Compatibility layer for block capabilities that need an adapter before they are exposed to BuildCraft.
 *
 * <p>NeoForge block capabilities are queried through the level, rather than directly from the block entity.
 * This class presents BuildCraft's Optional-style capability view over level-based NeoForge lookups.</p>
 */
public enum CompatCapTransfromer {
    INSTANCE;

//? if mc_26_x && (>=26.3) {
    private final Map<Class<?>, BiFunction<Object, Direction, ResourceHandler<FluidResource>>> fluidCapRegistry = new HashMap<>();
    private final List<BiFunction<Object, Direction, ResourceHandler<FluidResource>>> fluidCapFallbacks = new ArrayList<>();
//? } else {
    private final Map<Class<?>, BiFunction<Object, Direction, IFluidHandler>> fluidCapRegistry = new HashMap<>();
    private final List<BiFunction<Object, Direction, IFluidHandler>> fluidCapFallbacks = new ArrayList<>();
//?}

//? if mc_26_x && (>=26.3) {
    public ResourceHandler<FluidResource> transfromFluidCap(BlockEntity provider, Direction face) {
        if (provider == null || provider.isRemoved()) return null;
        BiFunction<Object, Direction, ResourceHandler<FluidResource>> function = fluidCapRegistry.get(provider.getClass());
//? } else {
    public IFluidHandler transfromFluidCap(BlockEntity provider, Direction face) {
        BiFunction<Object, Direction, IFluidHandler> function = fluidCapRegistry.get(provider.getClass());
//?}
        if (function != null) {
//? if mc_26_x && (>=26.3) {
            ResourceHandler<FluidResource> handler = function.apply(provider, face);
//? } else {
            IFluidHandler handler = function.apply(provider, face);
//?}
            if (handler != null) {
                return handler;
            }
        }
//? if mc_26_x && (>=26.3) {
        for (BiFunction<Object, Direction, ResourceHandler<FluidResource>> fallback : fluidCapFallbacks) {
            ResourceHandler<FluidResource> handler = fallback.apply(provider, face);
//? } else {
        for (BiFunction<Object, Direction, IFluidHandler> fallback : fluidCapFallbacks) {
            IFluidHandler handler = fallback.apply(provider, face);
//?}
            if (handler != null) {
                return handler;
            }
        }
//? if mc_26_x && (>=26.3) {
        return provider.getLevel() == null ? null : provider.getLevel().getCapability(
            Capabilities.Fluid.BLOCK, provider.getBlockPos(), face);
//? } else {
        return null;
//?}
    }

//? if mc_26_x && (>=26.3) {
    public void registryFluidCapTransform(Class<?> clazz, BiFunction<Object, Direction, ResourceHandler<FluidResource>> function) {
//? } else {
    public void registryFluidCapTransform(Class<?> clazz, BiFunction<Object, Direction, IFluidHandler> function) {
//?}
        fluidCapRegistry.put(clazz, function);
    }

    /** Registers a capability adapter that can inspect arbitrary optional-mod block entities. */
//? if mc_26_x && (>=26.3) {
    public void registerFluidCapFallback(BiFunction<Object, Direction, ResourceHandler<FluidResource>> function) {
//? } else {
    public void registerFluidCapFallback(BiFunction<Object, Direction, IFluidHandler> function) {
//?}
        if (function != null && !fluidCapFallbacks.contains(function)) {
            fluidCapFallbacks.add(function);
        }
    }

    public <E> Optional<E> getCap(
        BlockEntity provider,
        BlockCapability<E, Direction> capability,
        Direction face
    ) {
        if (provider == null || provider.getLevel() == null) {
            return Optional.empty();
        }

//? if mc_26_x && (>=26.3) {
        if (capability == Capabilities.Fluid.BLOCK) {
            @SuppressWarnings("unchecked")
            E value = (E) transfromFluidCap(provider, face);
            return Optional.ofNullable(value);
        }
//? } else {
//?}
        if (capability == CapUtil.CAP_FLUIDS) {
//? if mc_26_x && (>=26.3) {
//? } else {
            // Compatibility wrappers must get first chance: an optional mod may expose a native
            // handler but still require fluid-ID translation at the integration boundary.
            IFluidHandler transformed = transfromFluidCap(provider, face);
            if (transformed != null) {
                @SuppressWarnings("unchecked")
                E value = (E) transformed;
                return Optional.of(value);
            }

//?}
            @SuppressWarnings("unchecked")
            E value = (E) CapUtil.getFluidHandler(provider.getLevel(), provider.getBlockPos(), face);
            return Optional.ofNullable(value);
        }

        if (capability == CapUtil.CAP_ITEMS) {
            @SuppressWarnings("unchecked")
            E value = (E) CapUtil.getItemHandler(provider.getLevel(), provider.getBlockPos(), face);
            return Optional.ofNullable(value);
        }

        if (capability == CapUtil.CAP_FE) {
            @SuppressWarnings("unchecked")
            E value = (E) CapUtil.getEnergyStorage(provider.getLevel(), provider.getBlockPos(), face);
            return Optional.ofNullable(value);
        }

        return Optional.ofNullable(
            provider.getLevel().getCapability(capability, provider.getBlockPos(), face)
        );
    }
}
