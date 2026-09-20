package buildcraft.lib.platform.registry;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;

/** Internal lazy registry reference. This is not a vanilla Holder and is not part of API v2. */
public final class BCRegistryEntry<T> implements Supplier<T> {
    private final String path;
    private final ResourceLocation id;
    private final Supplier<? extends T> factory;
    private Supplier<? extends T> registered;
    private BooleanSupplier present = () -> false;
    BCRegistryEntry(String namespace, String path, Supplier<? extends T> factory) {
        this.path = path; this.id = RegistryNames.id(namespace, path); this.factory = Objects.requireNonNull(factory);
    }
    public String path() { return path; }
    public ResourceLocation getId() { return id; }
    public Supplier<? extends T> factory() { return factory; }
    public void bind(Supplier<? extends T> registered, BooleanSupplier present) {
        if (this.registered != null) throw new IllegalStateException("Registry entry bound twice: " + id);
        this.registered = Objects.requireNonNull(registered);
        this.present = Objects.requireNonNull(present);
    }
    public boolean isPresent() { return present.getAsBoolean(); }
    public boolean isBound() { return isPresent(); }
    public T value() { return get(); }
    @Override public T get() {
        if (registered == null) throw new IllegalStateException("Registry entry has not been bound: " + id);
        return registered.get();
    }
}
