package buildcraft.lib.internal.api.v2.energy;

import buildcraft.api.v2.energy.MjConnectionContext;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjPortDescriptor;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Internal loader bridge used by EnergyServiceImpl after direct API2 providers are checked. */
public final class MjRuntimeLookup {
    public interface Backend {
        Optional<MjPort> port(Level level, BlockPos pos, Direction side);
        Optional<MjPortDescriptor> descriptor(Level level, BlockPos pos, Direction side);
        default boolean canConnect(MjConnectionContext context) { return true; }
    }

    private static final Backend EMPTY = new Backend() {
        public Optional<MjPort> port(Level level, BlockPos pos, Direction side) { return Optional.empty(); }
        public Optional<MjPortDescriptor> descriptor(Level level, BlockPos pos, Direction side) { return Optional.empty(); }
    };

    private static volatile Backend backend = EMPTY;

    private MjRuntimeLookup() {}

    public static synchronized void install(Backend value) {
        Objects.requireNonNull(value, "value");
        if (backend != EMPTY && backend != value) {
            throw new IllegalStateException("MJ runtime lookup backend already installed");
        }
        backend = value;
    }

    public static Optional<MjPort> port(Level level, BlockPos pos, Direction side) {
        return backend.port(level, pos, side);
    }

    public static boolean canConnect(MjConnectionContext context) {
        Backend current = backend;
        return current == null || current.canConnect(context);
    }

    public static Optional<MjPortDescriptor> descriptor(Level level, BlockPos pos, Direction side) {
        return backend.descriptor(level, pos, side);
    }
}
