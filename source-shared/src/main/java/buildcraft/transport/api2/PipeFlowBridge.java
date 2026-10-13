package buildcraft.transport.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.pipe.PipeFlowDefinition;
import buildcraft.api.v2.pipe.PipeType;
import buildcraft.api.v2.pipe.PipeMedium;
import buildcraft.transport.internal.pipe.PipeFlowType;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

/** Loader-neutral flow registrations materialized to the legacy-compatible holder ABI. */
public final class PipeFlowBridge {
    private record Carrier(ResourceLocation flowId, boolean item) { }
    private static final Map<Carrier, PipeFlowType> MATERIALIZED = new ConcurrentHashMap<>();

    private PipeFlowBridge() { }

    public static PipeFlowType resolve(PipeType type, PipeFlowType fallback) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(fallback, "fallback");
        ResourceLocation id = type.flowTypeId().orElse(null);
        if (id == null) return fallback;
        PipeFlowDefinition<?> flow = BuildCraftApi.registry(BuildCraftRegistries.PIPE_FLOW_TYPES).get(id);
        if (flow == null) throw new IllegalStateException("Custom pipe flow is not registered: " + id);
        if (BuildCraftApi.registry(BuildCraftRegistries.PIPE_COMPONENT_TYPES).get(flow.componentType().id())
            != flow.componentType()) {
            throw new IllegalStateException("Flow component must be registered under its own ID: "
                + flow.componentType().id());
        }
        Carrier carrier = new Carrier(id, type.media().contains(PipeMedium.ITEM));
        return MATERIALIZED.computeIfAbsent(carrier, key -> new PipeFlowType(
            pipe -> key.item() ? new ApiItemPipeFlow(pipe, key.flowId()) : new ApiPipeFlow(pipe, key.flowId()),
            (pipe, saved) -> key.item() ? new ApiItemPipeFlow(pipe, key.flowId()) : new ApiPipeFlow(pipe, key.flowId())));
    }
}
