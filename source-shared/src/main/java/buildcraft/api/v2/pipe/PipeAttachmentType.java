package buildcraft.api.v2.pipe;

import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Registered attachment factory and optional persisted codec. Stateful attachments need a codec;
 * without one, the factory is also used on world reload and client creation with an empty stack
 * and an unknown actor.
 */
public final class PipeAttachmentType<A extends PipeAttachment> {
    private final ResourceLocation id;
    private final PipeAttachmentFactory<A> factory;
    private final PersistentType<A, OpaqueData> persistence;
    public PipeAttachmentType(ResourceLocation id, PipeAttachmentFactory<A> factory, PersistentType<A, OpaqueData> persistence) {
        this.id = Objects.requireNonNull(id, "id"); this.factory = Objects.requireNonNull(factory, "factory"); this.persistence = persistence;
    }
    public ResourceLocation id() { return id; }
    public A create(PipeAttachmentPlacementContext context) { return factory.create(context); }
    public Optional<PersistentType<A, OpaqueData>> persistence() { return Optional.ofNullable(persistence); }
}
