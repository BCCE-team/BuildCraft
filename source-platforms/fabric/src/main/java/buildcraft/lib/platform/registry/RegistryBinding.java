package buildcraft.lib.platform.registry;
// Loader boundary owner: net.fabricmc (implementation may delegate to vanilla/common contracts).

import java.util.Objects;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** Fabric registry binding. Catalog factories are evaluated only when this loader bootstrap binds the catalog. */
public final class RegistryBinding implements BCRegistryBinder {
    public static final RegistryBinding INSTANCE = new RegistryBinding();
    private RegistryBinding() { }

    public static RegistryBinding direct() { return INSTANCE; }

    @Override
    public <T> void register(BCDeferredRegister<T> catalog) {
        Registry<T> registry = findRegistry(catalog.registryId());
        catalog.bindEntries(new BCDeferredRegister.EntryBinder<>() {
            @Override
            public <I extends T> void bind(BCRegistryEntry<I> entry) {
                I value = Registry.register(registry, entry.getId(), entry.factory().get());
                entry.bind(() -> value, () -> true);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> Registry<T> findRegistry(String registryId) {
        ResourceLocation id = Objects.requireNonNull(ResourceLocation.tryParse(registryId), "registryId");
        Registry<?> registry = BuiltInRegistries.REGISTRY.get(id);
        if (registry == null) {
            throw new IllegalArgumentException("Unknown Minecraft registry: " + registryId);
        }
        return (Registry<T>) registry;
    }
}
