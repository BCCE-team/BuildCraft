package buildcraft.lib.platform.registry;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Native registration mechanism. Registration order, event bus and lazy factory execution remain loader-owned. */
public final class RegistryBinding implements BCRegistryBinder {
    private final IEventBus bus;
    private RegistryBinding(IEventBus bus) { this.bus = java.util.Objects.requireNonNull(bus); }
    public static RegistryBinding on(IEventBus bus) { return new RegistryBinding(bus); }
    public static <T> void register(BCDeferredRegister<T> catalog, IEventBus bus) { on(bus).register(catalog); }
    @Override public <T> void register(BCDeferredRegister<T> catalog) {
        ResourceKey<Registry<T>> key = ResourceKey.createRegistryKey(ResourceLocation.parse(catalog.registryId()));
        DeferredRegister<T> nativeRegister = DeferredRegister.create(key, catalog.namespace());
        catalog.bindEntries(new BCDeferredRegister.EntryBinder<T>() {
            public <I extends T> void bind(BCRegistryEntry<I> entry) { bindEntry(nativeRegister, entry); }
        });
        nativeRegister.register(bus);
    }
    private static <B, I extends B> void bindEntry(DeferredRegister<B> register, BCRegistryEntry<I> entry) {
        var holder = register.register(entry.path(), entry.factory());
        entry.bind(holder, holder::isBound);
    }
}
