package buildcraft.transport.api2;

import buildcraft.api.v2.pipe.PipeComponent;
import buildcraft.api.v2.pipe.PipeMedium;
import buildcraft.api.v2.pipe.PipeResourceService;
import buildcraft.api.v2.pipe.PipeResourceTickComponent;
import buildcraft.api.v2.pipe.PipeResourceTickContext;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.flow.PipeFlowFluids;
import buildcraft.transport.pipe.flow.PipeFlowForgeEnergy;
import buildcraft.transport.pipe.flow.PipeFlowPower;
import net.minecraft.world.level.Level;

/** Dispatches addon resource handlers on the same server tick as the native pipe flow. */
public final class PipeResourceRuntime {
    private PipeResourceRuntime() { }

    public static void tick(Pipe pipe) {
        Level level = pipe.getHolder().getPipeWorld();
        if (level == null || level.isClientSide() || level.getServer() == null
            || !level.getServer().isSameThread()) return;
        PipeMedium medium = pipe.flow instanceof PipeFlowFluids ? PipeMedium.FLUID
            : pipe.flow instanceof PipeFlowPower ? PipeMedium.MJ
            : pipe.flow instanceof PipeFlowForgeEnergy ? PipeMedium.EXTERNAL_ENERGY : null;
        if (medium == null) return;
        Context context = null;
        for (PipeComponent component : pipe.components()) {
            if (!(component instanceof PipeResourceTickComponent listener)) continue;
            if (context == null) context = new Context(pipe, level, medium);
            try {
                listener.onResourceTick(context);
            } catch (RuntimeException ex) {
                BCLog.logger.warn("Pipe resource callback failed at {} for {}", pipe.position(), component.typeId(), ex);
            }
        }
        if (context != null) context.open = false;
    }

    private static final class Context implements PipeResourceTickContext {
        private final PipeView view;
        private final Level world;
        private final PipeMedium medium;
        private boolean open = true;

        private Context(Pipe pipe, Level world, PipeMedium medium) {
            this.view = ReadOnlyPipeView.of(pipe);
            this.world = world;
            this.medium = medium;
        }
        private void check() { if (!open) throw new IllegalStateException("Expired resource-tick context"); }
        @Override public PipeView pipe() { check(); return view; }
        @Override public Level world() { check(); return world; }
        @Override public PipeMedium medium() { check(); return medium; }
        @Override public PipeResourceService transfers() { check(); return PipeResourceServiceImpl.INSTANCE; }
    }
}
