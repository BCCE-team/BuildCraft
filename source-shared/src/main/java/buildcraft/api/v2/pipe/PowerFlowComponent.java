package buildcraft.api.v2.pipe;

/** Public MJ/FE request, transfer and profile hooks; native buffers remain authoritative. */
public interface PowerFlowComponent extends PipeComponent {
    /** Configure throughput, receiver behaviour or resistance without exposing native pipe events. */
    default PowerFlowSettings configure(PipeView pipe, PowerFlowSettings settings) { return settings; }

    /** Reduce an offered amount before the native flow performs the associated action. */
    default long limitPower(PowerMovementContext context) { return context.offered(); }
}
