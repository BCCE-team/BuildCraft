package buildcraft.api.v2.event;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Immutable registration metadata; not a mutable event object. */
public record WorldEventSubscription(ResourceLocation id, int priority, WorldEventListener listener) {
    public WorldEventSubscription {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(listener, "listener");
    }
}
