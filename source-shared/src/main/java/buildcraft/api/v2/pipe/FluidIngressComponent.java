package buildcraft.api.v2.pipe;

import buildcraft.api.v2.fluid.FluidVolume;

/** Typed interception hook for incoming fluid transfers. */
public interface FluidIngressComponent extends PipeComponent {
    default FluidIngressResult insert(FluidIngressContext context, FluidVolume offered) {
        return FluidIngressResult.pass();
    }
}
