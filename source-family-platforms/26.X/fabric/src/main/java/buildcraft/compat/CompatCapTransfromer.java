/* Copyright (c) 2026 the BCCE contributors. Licensed under MPL-2.0. */
package buildcraft.compat;

import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.platform.capability.BlockCapability;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Compatibility mapper for optional mod tile capabilities on Fabric.
 * All standard lookups end at CapUtil and its Fabric Transfer adapters.
 */
public enum CompatCapTransfromer {
    INSTANCE;

    private final Map<Class<?>, BiFunction<Object, Direction, IFluidHandler>> registered = new HashMap<>();
    private final List<BiFunction<Object, Direction, IFluidHandler>> fallbacks = new ArrayList<>();

    public IFluidHandler transfromFluidCap(BlockEntity tile, Direction face) {
        if (tile == null || tile.isRemoved()) return null;
        BiFunction<Object, Direction, IFluidHandler> registeredAdapter = registered.get(tile.getClass());
        if (registeredAdapter != null) {
            IFluidHandler result = registeredAdapter.apply(tile, face);
            if (result != null) return result;
        }
        for (BiFunction<Object, Direction, IFluidHandler> fallback : fallbacks) {
            IFluidHandler result = fallback.apply(tile, face);
            if (result != null) return result;
        }
        return tile.getLevel() == null ? null :
            CapUtil.getFluidHandler(tile.getLevel(), tile.getBlockPos(), face);
    }

    public void registryFluidCapTransform(Class<?> type, BiFunction<Object, Direction, IFluidHandler> adapter) {
        if (type != null && adapter != null) registered.put(type, adapter);
    }

    public void registerFluidCapFallback(BiFunction<Object, Direction, IFluidHandler> adapter) {
        if (adapter != null && !fallbacks.contains(adapter)) fallbacks.add(adapter);
    }

    @SuppressWarnings("unchecked")
    public <E> Optional<E> getCap(BlockEntity tile, BlockCapability<E, Direction> capability, Direction face) {
        if (tile == null || tile.isRemoved() || tile.getLevel() == null || capability == null) {
            return Optional.empty();
        }
        if (capability == CapUtil.CAP_FLUIDS) {
            return Optional.ofNullable((E) transfromFluidCap(tile, face));
        }
        return Optional.ofNullable(CapUtil.getCapability(tile.getLevel(), tile.getBlockPos(), capability, face));
    }
}
