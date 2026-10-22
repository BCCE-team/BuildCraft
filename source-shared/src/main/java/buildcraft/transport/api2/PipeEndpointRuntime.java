package buildcraft.transport.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.pipe.PipeAttachment;
import buildcraft.api.v2.pipe.PipeConnectionContext;
import buildcraft.api.v2.pipe.PipeConnectionDecision;
import buildcraft.api.v2.pipe.PipeEndpointProvider;
import buildcraft.api.v2.pipe.PipeExternalEndpoint;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.transport.internal.pluggable.PipePluggable;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Runtime adapters for registered endpoints and installed side attachments. */
public final class PipeEndpointRuntime {
    private PipeEndpointRuntime() { }

    public static Optional<PipeExternalEndpoint> find(PipeConnectionContext context) {
        Objects.requireNonNull(context, "context");
        Level level = context.level();
        if (level.isClientSide() || !level.hasChunkAt(context.pipe().position().relative(context.side()))) {
            return Optional.empty();
        }
        PipeConnectionContext safe = new PipeConnectionContext(level, ReadOnlyPipeView.of(context.pipe()),
            context.side(), context.neighbourState());
        for (PipeEndpointProvider provider : BuildCraftApi.registry(BuildCraftRegistries.PIPE_ENDPOINT_PROVIDERS).values()) {
            try {
                Optional<PipeExternalEndpoint> found = Objects.requireNonNull(provider.find(safe), "endpoint lookup");
                if (found.isPresent()) return found;
            } catch (RuntimeException ex) {
                BCLog.logger.warn("External pipe endpoint provider failed at {} side {}",
                    context.pipe().position(), context.side(), ex);
            }
        }
        return Optional.empty();
    }

    public static Optional<PipeExternalEndpoint> find(Level level, PipeView pipe, Direction side) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pipe, "pipe");
        Objects.requireNonNull(side, "side");
        if (level.isClientSide() || !level.hasChunkAt(pipe.position().relative(side))) return Optional.empty();
        return find(new PipeConnectionContext(level, ReadOnlyPipeView.of(pipe), side,
            level.getBlockState(pipe.position().relative(side))));
    }

    public static PipeConnectionDecision attachmentDecision(PipePluggable pluggable,
        PipeConnectionContext context) {
        if (!(pluggable instanceof ApiPipePluggable api)) return PipeConnectionDecision.PASS;
        try {
            return Objects.requireNonNull(api.attachment().connection(context), "attachment decision");
        } catch (RuntimeException ex) {
            BCLog.logger.warn("Pipe attachment connection failed at {} side {}",
                context.pipe().position(), context.side(), ex);
            return PipeConnectionDecision.DENY;
        }
    }

    public static <T> Optional<T> attachmentPort(PipePluggable plug,
        Function<PipeAttachment, Optional<T>> port) {
        if (!(plug instanceof ApiPipePluggable api)) return Optional.empty();
        try {
            return Objects.requireNonNull(port.apply(api.attachment()), "attachment port");
        } catch (RuntimeException ex) {
            BCLog.logger.warn("Attachment port provider failed on side {}", plug.side, ex);
            return Optional.empty();
        }
    }

    /** Only connect a physical side when both endpoints expose a matching transport medium. */
    public static boolean canConnect(PipeExternalEndpoint endpoint, PipeView pipe, Direction side) {
        try {
            PipeView safe = ReadOnlyPipeView.of(pipe);
            if (!endpoint.canConnect(safe)) return false;
            return (nonEmpty(endpoint.itemPort()) && safe.itemPort(side).isPresent())
                || (nonEmpty(endpoint.fluidPort()) && safe.fluidPort(side).isPresent())
                || (nonEmpty(endpoint.mjPort()) && safe.mjPort(side).isPresent())
                || (nonEmpty(endpoint.externalEnergyPort()) && safe.externalEnergyPort(side).isPresent());
        } catch (RuntimeException ex) {
            BCLog.logger.warn("External pipe endpoint connection failed at {}", pipe.position(), ex);
            return false;
        }
    }

    public static float extension(PipeExternalEndpoint endpoint) {
        try {
            float value = endpoint.extension();
            return Float.isFinite(value) ? Math.max(-0.25f, Math.min(0.5f, value)) : 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static boolean nonEmpty(Optional<?> option) {
        return option != null && option.isPresent();
    }
}
