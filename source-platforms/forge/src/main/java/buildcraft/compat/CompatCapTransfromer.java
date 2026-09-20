package buildcraft.compat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import buildcraft.lib.misc.CapUtil;
import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.capability.IFluidHandler;

public enum CompatCapTransfromer {
    INSTANCE;

    private final Map<Class<?>, BiFunction<Object, Direction, IFluidHandler>> fluidCapRegistry = new HashMap<>();
    private final List<BiFunction<Object, Direction, IFluidHandler>> fluidCapFallbacks = new ArrayList<>();

    public IFluidHandler transfromFluidCap(ICapabilityProvider provider, Direction face) {
        BiFunction<Object, Direction, IFluidHandler> function = fluidCapRegistry.get(provider.getClass());
        if (function != null) {
            IFluidHandler handler = function.apply(provider, face);
            if (handler != null) {
                return handler;
            }
        }
        for (BiFunction<Object, Direction, IFluidHandler> fallback : fluidCapFallbacks) {
            IFluidHandler handler = fallback.apply(provider, face);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }

    public void registryFluidCapTransform(Class<?> clazz, BiFunction<Object, Direction, IFluidHandler> function) {
        fluidCapRegistry.put(clazz, function);
    }

    /** Registers a capability adapter that can inspect arbitrary optional-mod block entities. */
    public void registerFluidCapFallback(BiFunction<Object, Direction, IFluidHandler> function) {
        if (function != null && !fluidCapFallbacks.contains(function)) {
            fluidCapFallbacks.add(function);
        }
    }

    public <E> LazyOptional<E> getCap(ICapabilityProvider provider, Capability<E> capability, Direction face) {
        if (capability == CapUtil.CAP_FLUIDS) {
            // Compatibility wrappers must get first chance: an optional mod may expose a native
            // Forge handler but still require fluid-ID translation at the integration boundary.
            IFluidHandler transformed = transfromFluidCap(provider, face);
            if (transformed != null) {
                return LazyOptional.of(() -> transformed).cast();
            }
        }
        return provider.getCapability(capability, face);
    }
}
