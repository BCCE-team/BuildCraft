package buildcraft.transport.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.pipe.PipeAttachment;
import buildcraft.api.v2.pipe.PipeAttachmentType;
import buildcraft.api.v2.registry.ApiRegistry;
import buildcraft.transport.internal.pluggable.PluggableDefinition;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.pipe.Pipe;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** Adapts addon attachments and existing BCCE pluggables to the same pipe holder. */
public final class PipeAttachmentBridge {
    private static final Map<ResourceLocation, PluggableDefinition> ADAPTERS = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> LEGACY = ConcurrentHashMap.newKeySet();

    private PipeAttachmentBridge() {}

    public static void ensureRegistered(ResourceLocation id, PluggableDefinition definition) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definition, "definition");
        ApiRegistry<PipeAttachmentType<?>> registry = BuildCraftApi.registry(BuildCraftRegistries.PIPE_ATTACHMENT_TYPES);
        if (registry.get(id) != null) return;

        PipeAttachmentType<LegacyPipeAttachmentView> type = new PipeAttachmentType<>(
            id,
            context -> {
                if (!(context.pipe() instanceof Pipe pipe)) {
                    throw new IllegalArgumentException("BCCE attachment placement requires a live BuildCraft pipe");
                }
                if (definition.creator == null) {
                    throw new IllegalStateException("Attachment " + id + " has no simple runtime creator");
                }
                PipePluggable pluggable = definition.creator.createSimplePluggable(
                    definition, pipe.getHolder(), context.side()
                );
                return new LegacyPipeAttachmentView(id, context.side(), pluggable);
            },
            null
        );
        registry.register(id, type, () -> id.getNamespace());
        LEGACY.add(id);
    }

    public static boolean isLegacy(ResourceLocation id) { return LEGACY.contains(id); }

    /** Lookup is lazy so direct API registry registration also works, including before world restore. */
    public static PluggableDefinition definition(ResourceLocation id) {
        PipeAttachmentType<?> type = BuildCraftApi.registry(BuildCraftRegistries.PIPE_ATTACHMENT_TYPES).get(id);
        if (type == null) return null;
        return ADAPTERS.computeIfAbsent(id, ignored -> new PluggableDefinition(id,
            (definition, holder, side, tag) -> ApiPipePluggable.load(definition, holder, side, type, tag),
            (definition, holder, side, buffer) -> ApiPipePluggable.fromBuffer(definition, holder, side, type, buffer)));
    }

    public static PipeAttachment attachment(PipePluggable pluggable, Direction side) {
        if (pluggable == null || pluggable == PipePluggable.EMPTY || pluggable.definition == null) return null;
        if (pluggable instanceof ApiPipePluggable api) return api.attachment();
        return new LegacyPipeAttachmentView(pluggable.definition.identifier, side, pluggable);
    }
}
