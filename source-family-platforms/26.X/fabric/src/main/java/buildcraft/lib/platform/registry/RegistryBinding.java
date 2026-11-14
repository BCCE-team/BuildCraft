package buildcraft.lib.platform.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Minecraft 26.3 Fabric registry bridge. BCDeferredRegister remains the source
 * of names and creation order; Fabric binds descriptors when the module is
 * bootstrapped rather than maintaining a NeoForge-specific DeferredRegister.
 */
public final class RegistryBinding implements BCRegistryBinder {
    private static final RegistryBinding INSTANCE = new RegistryBinding();
    private RegistryBinding() {}

    public static RegistryBinding direct() {
        return INSTANCE;
    }

    public <T> void register(BCDeferredRegister<T> catalog) {
        Registry<T> registry = lookup(catalog.registryId());
        catalog.bindEntries(new BCDeferredRegister.EntryBinder<>() {
            public <I extends T> void bind(BCRegistryEntry<I> entry) {
                if (registry.containsKey(entry.getId())) {
                    throw new IllegalStateException("Duplicate BuildCraft registry ID " + entry.getId());
                }
                I registered = Registry.register(registry, entry.getId(), entry.factory().get());
                // Do not expose a phantom present entry if a native registration fails.
                entry.bind(() -> registered, () -> registry.containsKey(entry.getId()));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> Registry<T> lookup(String registryId) {
        Identifier id = Identifier.parse(registryId);
        Registry<?> registry = BuiltInRegistries.REGISTRY.getValue(id);
        if (registry == null) {
            throw new IllegalArgumentException("Unknown built-in registry " + id);
        }
        return (Registry<T>) registry;
    }
}
