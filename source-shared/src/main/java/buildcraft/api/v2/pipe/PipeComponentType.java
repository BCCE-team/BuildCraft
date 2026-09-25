package buildcraft.api.v2.pipe;

import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class PipeComponentType<C extends PipeComponent> {
    private final ResourceLocation id;
    private final PipeComponentFactory<C> factory;
    private final PersistentType<C, OpaqueData> persistence;
    private final PipeComponentState<C, ?> statePersistence;

    public PipeComponentType(ResourceLocation id, PipeComponentFactory<C> factory, PersistentType<C, OpaqueData> persistence) {
        this(id, factory, persistence, null);
    }

    public PipeComponentType(
        ResourceLocation id,
        PipeComponentFactory<C> factory,
        PersistentType<C, OpaqueData> persistence,
        PipeComponentState<C, ?> statePersistence
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.persistence = persistence;
        this.statePersistence = statePersistence;
        if (persistence != null && statePersistence != null) {
            throw new IllegalArgumentException("Use either whole-component persistence or state persistence, not both");
        }
    }

    public ResourceLocation id() { return id; }
    public C create(PipeView pipe) { return factory.create(pipe); }
    public Optional<PersistentType<C, OpaqueData>> persistence() { return Optional.ofNullable(persistence); }
    public Optional<PipeComponentState<C, ?>> statePersistence() { return Optional.ofNullable(statePersistence); }
}
