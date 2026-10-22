package buildcraft.api.v2.pipe;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Registered flow implementation, including a factory, persistent decoder and sync channels. */
public final class PipeFlowDefinition<C extends PipeFlowComponent> {
    private final ResourceLocation id;
    private final PipeComponentType<C> componentType;

    public PipeFlowDefinition(ResourceLocation id, PipeComponentType<C> componentType) {
        this.id = Objects.requireNonNull(id, "id");
        this.componentType = Objects.requireNonNull(componentType, "componentType");
        if (componentType.persistence().isEmpty()) {
            throw new IllegalArgumentException("Custom pipe flow requires persistent state: " + id);
        }
        if (!componentType.id().equals(componentType.persistence().orElseThrow().id())) {
            throw new IllegalArgumentException("Custom runtime component persistence ID must match the component ID");
        }
    }
    public ResourceLocation id() { return id; }
    public PipeComponentType<C> componentType() { return componentType; }
}
