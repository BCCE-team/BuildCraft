package buildcraft.transport.internal.pipe;

import javax.annotation.Nullable;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.transport.internal.pluggable.PipePluggable;
import net.minecraft.core.Direction;

/**
 * Loader-neutral fluid-pipe flow contract for the legacy family.
 *
 * <p>The public surface deliberately uses API v2 fluid values and {@link OperationMode}; native Forge/Fabric
 * storage types belong to the platform adapter. This lets the same pipe-flow implementation run against Forge
 * capabilities and Fabric Transfer API without duplicating transport gameplay.</p>
 */
public interface IFlowFluid {
    /**
     * Pulls fluid from the adjacent endpoint into this pipe.
     *
     * @param millibuckets maximum amount to pull
     * @param from pipe side containing the source endpoint
     * @param filter exact preferred variant, or {@code null}/empty for the pipe's current variant / any variant
     * @param mode simulation or execution
     * @return the volume accepted by the pipe
     */
    FluidVolume tryExtractFluid(
        int millibuckets,
        Direction from,
        @Nullable FluidVolume filter,
        OperationMode mode
    );

    /**
     * Matcher-based extraction used by filtered wooden fluid pipes.
     */
    FluidVolume tryExtractFluidMatching(
        int millibuckets,
        Direction from,
        FluidMatcher matcher,
        OperationMode mode
    );

    /**
     * Inserts fluid directly into the pipe centre. Intended for pipe behaviours/components rather than external
     * block-storage exposure.
     */
    int insertFluidsForce(FluidVolume fluid, @Nullable Direction from, OperationMode mode);

    /**
     * Extracts fluid directly from one pipe section. Intended for {@link PipeBehaviour} and {@link PipePluggable}
     * implementations only.
     */
    FluidVolume extractFluidsForce(int min, int max, @Nullable Direction section, OperationMode mode);

    /**
     * Native-loader storage exposure delegates here so section direction/cooldown semantics remain common.
     */
    int insertFluidsExternal(FluidVolume fluid, Direction from, OperationMode mode);

    /** Returns the fluid currently visible in the requested external section. */
    FluidVolume getFluidInSection(Direction side);

    /** Per-section storage capacity in mB. */
    int getFluidSectionCapacity();

    /** Whether an external insertion of the given fluid is currently valid for that section. */
    boolean isFluidValidForSection(Direction side, FluidVolume fluid);
}
