package buildcraft.api.v2.pipe;

import buildcraft.api.v2.fluid.FluidAmount;
import java.util.Objects;

/** PASS leaves the transfer to the base pipe flow; CONSUMED reports component-handled input. */
public record FluidIngressResult(Action action, FluidAmount accepted) {
    public enum Action { PASS, CONSUMED }

    public FluidIngressResult {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(accepted, "accepted");
        if (action == Action.PASS && !accepted.isZero()) {
            throw new IllegalArgumentException("PASS must not report accepted fluid");
        }
    }

    public static FluidIngressResult pass() { return new FluidIngressResult(Action.PASS, FluidAmount.ZERO); }
    public static FluidIngressResult consumed(FluidAmount amount) { return new FluidIngressResult(Action.CONSUMED, amount); }
    public boolean handled() { return action == Action.CONSUMED; }
}
