//? source if >=26.3
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
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/**
 * Compatibility layer for block capabilities that need an adapter before they are exposed to BuildCraft.
 *
 * <p>NeoForge block capabilities are queried through the level, rather than directly from the block entity.
 * This class presents BuildCraft's Optional-style capability view over level-based NeoForge lookups.</p>
 */
public enum CompatCapTransfromer {
    INSTANCE;

    private final Map<Class<?>, BiFunction<Object, Direction, ResourceHandler<FluidResource>>> fluidCapRegistry = new HashMap<>();
    private final List<BiFunction<Object, Direction, ResourceHandler<FluidResource>>> fluidCapFallbacks = new ArrayList<>();

    public ResourceHandler<FluidResource> transfromFluidCap(BlockEntity provider, Direction face) {
        if (provider == null || provider.isRemoved()) return null;
        BiFunction<Object, Direction, ResourceHandler<FluidResource>> function = fluidCapRegistry.get(provider.getClass());
        if (function != null) {
            ResourceHandler<FluidResource> handler = function.apply(provider, face);
            if (handler != null) {
                return handler;
            }
        }
        for (BiFunction<Object, Direction, ResourceHandler<FluidResource>> fallback : fluidCapFallbacks) {
            ResourceHandler<FluidResource> handler = fallback.apply(provider, face);
            if (handler != null) {
                return handler;
            }
        }
        return provider.getLevel() == null ? null : provider.getLevel().getCapability(
            Capabilities.Fluid.BLOCK, provider.getBlockPos(), face);
    }

    public void registryFluidCapTransform(Class<?> clazz, BiFunction<Object, Direction, ResourceHandler<FluidResource>> function) {
        fluidCapRegistry.put(clazz, function);
    }

    /** Registers a capability adapter that can inspect arbitrary optional-mod block entities. */
    public void registerFluidCapFallback(BiFunction<Object, Direction, ResourceHandler<FluidResource>> function) {
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

        if (capability == Capabilities.Fluid.BLOCK) {
            @SuppressWarnings("unchecked")
            E value = (E) transfromFluidCap(provider, face);
            return Optional.ofNullable(value);
        }
        if (capability == CapUtil.CAP_FLUIDS) {
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
