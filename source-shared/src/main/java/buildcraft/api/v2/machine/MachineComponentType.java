package buildcraft.api.v2.machine;

import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Stable component descriptor; factories create executable instances inside a MachineHost. */
public final class MachineComponentType<C extends MachineComponent> {
    private final ResourceLocation id;
    private final PersistentType<C, OpaqueData> persistence;
    private final MachineComponentFactory<C> factory;
    private final MachineComponentPresentation<C, ?> presentation;

    /** Retained for binary and source compatibility with component-only definitions. */
    public MachineComponentType(ResourceLocation id, PersistentType<C, OpaqueData> persistence) {
        this(id, persistence, null, null);
    }

    public MachineComponentType(ResourceLocation id, PersistentType<C, OpaqueData> persistence,
        MachineComponentFactory<C> factory) {
        this(id, persistence, factory, null);
    }

    public MachineComponentType(ResourceLocation id, PersistentType<C, OpaqueData> persistence,
        MachineComponentFactory<C> factory, MachineComponentPresentation<C, ?> presentation) {
        this.id = Objects.requireNonNull(id, "id");
        this.persistence = persistence;
        this.factory = factory;
        this.presentation = presentation;
        if (persistence != null && !id.equals(persistence.id())) {
            throw new IllegalArgumentException("Component persistence id must match " + id);
        }
        if (presentation != null && !id.equals(presentation.codec().id())) {
            throw new IllegalArgumentException("Component presentation id must match " + id);
        }
    }

    public ResourceLocation id() { return id; }
    public PersistentType<C, OpaqueData> persistence() { return persistence; }
    public Optional<PersistentType<C, OpaqueData>> optionalPersistence() { return Optional.ofNullable(persistence); }
    public Optional<MachineComponentFactory<C>> factory() { return Optional.ofNullable(factory); }
    public Optional<MachineComponentPresentation<C, ?>> presentation() { return Optional.ofNullable(presentation); }

    @Override public boolean equals(Object other) {
        return other instanceof MachineComponentType<?> type && id.equals(type.id)
            && Objects.equals(persistence, type.persistence)
            && Objects.equals(factory, type.factory)
            && Objects.equals(presentation, type.presentation);
    }
    @Override public int hashCode() { return Objects.hash(id, persistence, factory, presentation); }
    @Override public String toString() { return "MachineComponentType[" + id + "]"; }
}
