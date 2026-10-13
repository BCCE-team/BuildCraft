package buildcraft.api.v2.pipe;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Custom behaviour installed alongside any compatible built-in or custom flow. */
public final class PipeBehaviourDefinition<C extends PipeBehaviourComponent> {
    private final ResourceLocation id;
    private final PipeComponentType<C> componentType;

    public PipeBehaviourDefinition(ResourceLocation id, PipeComponentType<C> componentType) {
        this.id = Objects.requireNonNull(id, "id");
        this.componentType = Objects.requireNonNull(componentType, "componentType");
        if (componentType.persistence().isEmpty()) {
            throw new IllegalArgumentException("Custom pipe behaviour requires persistent state: " + id);
        }
        if (!componentType.id().equals(componentType.persistence().orElseThrow().id())) {
            throw new IllegalArgumentException("Custom runtime component persistence ID must match the component ID");
        }
    }
    public ResourceLocation id() { return id; }
    public PipeComponentType<C> componentType() { return componentType; }
}
