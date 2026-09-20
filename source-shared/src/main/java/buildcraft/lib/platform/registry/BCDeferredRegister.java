package buildcraft.lib.platform.registry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Registry;

/** Ordered content descriptor. Factories are evaluated only by native RegisterEvent, never by planning/binding. */
public final class BCDeferredRegister<T> {
    private final String registryId;
    private final String namespace;
    private final List<BCRegistryEntry<? extends T>> entries = new ArrayList<>();
    private final Set<String> names = new HashSet<>();
    public interface EntryBinder<T> { <I extends T> void bind(BCRegistryEntry<I> entry); }
    private EntryBinder<T> binding;
    private BCDeferredRegister(String registryId, String namespace) {
        this.registryId = Objects.requireNonNull(registryId); this.namespace = Objects.requireNonNull(namespace);
    }
    public static <T> BCDeferredRegister<T> create(String registryId, String namespace) {
        return new BCDeferredRegister<>(registryId, namespace);
    }
    public static <T> BCDeferredRegister<T> create(ResourceKey<? extends Registry<T>> key, String namespace) {
        return create(RegistryNames.key(key), namespace);
    }
    public String registryId() { return registryId; }
    public String namespace() { return namespace; }
    public List<BCRegistryEntry<? extends T>> entries() { return List.copyOf(entries); }
    public <I extends T> BCRegistryEntry<I> register(String name, Supplier<? extends I> factory) {
        if (names.contains(name)) throw new IllegalArgumentException("Duplicate registry entry: " + namespace + ":" + name);
        BCRegistryEntry<I> entry = new BCRegistryEntry<>(namespace, name, factory);
        // A rejected native late registration must not leave a phantom descriptor.
        if (binding != null) binding.bind(entry);
        names.add(name);
        entries.add(entry);
        return entry;
    }
    public <I extends T> BCRegistryEntry<I> register(String name, Function<ResourceLocation, ? extends I> factory) {
        ResourceLocation id = RegistryNames.id(namespace, name);
        return register(name, () -> factory.apply(id));
    }
    public void register(BCRegistryBinder binder) { binder.register(this); }
    /** Binding-side guard. Called before attaching the native register to the mod bus. */
    public void bindEntries(EntryBinder<T> binding) {
        if (this.binding != null) throw new IllegalStateException("Registry already bound: " + registryId + " / " + namespace);
        this.binding = Objects.requireNonNull(binding);
        for (BCRegistryEntry<? extends T> entry : entries) binding.bind(entry);
    }
}
