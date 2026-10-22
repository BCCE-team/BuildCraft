package buildcraft.transport.api2;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.pipe.FluidMovementComponent;
import buildcraft.api.v2.pipe.FluidMovementContext;
import buildcraft.api.v2.pipe.FluidMovementPhase;
import buildcraft.api.v2.pipe.PipeComponent;
import buildcraft.api.v2.pipe.PipeMedium;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.api.v2.pipe.PowerFlowComponent;
import buildcraft.api.v2.pipe.PowerFlowSettings;
import buildcraft.api.v2.pipe.PowerMovementContext;
import buildcraft.api.v2.pipe.PowerMovementPhase;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.pipe.Pipe;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Bridges native fluid and energy events to stable addon components without exposing native flow classes. */
public final class PipeFlowEventRuntime {
    private PipeFlowEventRuntime() { }

    private static Pipe activePipe(IPipe nativePipe) {
        if (!(nativePipe instanceof Pipe pipe)) return null;
        Level world = pipe.getHolder().getPipeWorld();
        return world != null && !world.isClientSide() && world.getServer() != null
            && world.getServer().isSameThread() ? pipe : null;
    }

    public static boolean hasFluidListeners(IPipe nativePipe) {
        if (!(nativePipe instanceof Pipe pipe)) return false;
        for (PipeComponent component : pipe.components()) {
            if (component instanceof FluidMovementComponent) return true;
        }
        return false;
    }

    public static int fluidLimit(IPipe nativePipe, FluidMovementPhase phase, Direction face,
                                 FluidVolume fluid, int offered, int centreCapacity, OperationMode mode) {
        if (offered <= 0) return 0;
        Pipe pipe = activePipe(nativePipe);
        if (pipe == null) return offered;
        PipeView view = null;
        int allowed = offered;
        for (PipeComponent component : pipe.components()) {
            if (!(component instanceof FluidMovementComponent listener)) continue;
            try {
                if (view == null) view = ReadOnlyPipeView.of(pipe);
                int next = listener.limitFluidMovement(new FluidMovementContext(
                    view, phase, face, fluid, allowed, Math.max(0, centreCapacity), mode));
                allowed = Math.max(0, Math.min(allowed, next));
            } catch (RuntimeException failure) {
                BCLog.logger.warn("Fluid movement callback failed at {} for {}", pipe.position(), component.typeId(), failure);
                return 0;
            }
            if (allowed == 0) return 0;
        }
        return allowed;
    }

    public static PowerFlowSettings configure(IPipe nativePipe, PowerFlowSettings settings) {
        Objects.requireNonNull(settings, "settings");
        Pipe pipe = activePipe(nativePipe);
        if (pipe == null) return settings;
        PowerFlowSettings current = settings;
        PipeView view = null;
        for (PipeComponent component : pipe.components()) {
            if (!(component instanceof PowerFlowComponent listener)) continue;
            try {
                if (view == null) view = ReadOnlyPipeView.of(pipe);
                PowerFlowSettings next = Objects.requireNonNull(listener.configure(view, current), "flow settings");
                if (next.medium() != current.medium()) throw new IllegalArgumentException("cannot change power medium");
                // A listener cannot re-enable transfer after another listener (or native behaviour) disabled it.
                if (current.disabled() && !next.disabled()) throw new IllegalArgumentException("cannot re-enable disabled flow");
                current = next;
            } catch (RuntimeException failure) {
                BCLog.logger.warn("Power configuration callback failed at {} for {}", pipe.position(), component.typeId(), failure);
                // Preserve the native settings on failure, rather than disabling otherwise healthy pipes.
            }
        }
        return current;
    }

    public static long powerLimit(IPipe nativePipe, PipeMedium medium, PowerMovementPhase phase,
                                  Direction face, long offered, OperationMode mode) {
        if (offered <= 0) return 0;
        Pipe pipe = activePipe(nativePipe);
        if (pipe == null) return offered;
        long allowed = offered;
        PipeView view = null;
        for (PipeComponent component : pipe.components()) {
            if (!(component instanceof PowerFlowComponent listener)) continue;
            try {
                if (view == null) view = ReadOnlyPipeView.of(pipe);
                long next = listener.limitPower(new PowerMovementContext(view, medium, phase, face, allowed, mode));
                allowed = Math.max(0, Math.min(allowed, next));
            } catch (RuntimeException failure) {
                BCLog.logger.warn("Power flow callback failed at {} for {}", pipe.position(), component.typeId(), failure);
                return 0;
            }
            if (allowed == 0) return 0;
        }
        return allowed;
    }
}
