package buildcraft.api.v2.reload;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Registered during type registration; values are replaced on resource reload. */
public record ScriptableDefinitionType<T>(ResourceLocation id, Class<T> javaType, DefinitionDecoder<T> decoder) {
    public ScriptableDefinitionType {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(javaType, "javaType");
        Objects.requireNonNull(decoder, "decoder");
    }
}
