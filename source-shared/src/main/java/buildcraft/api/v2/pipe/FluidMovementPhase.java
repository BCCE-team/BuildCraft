package buildcraft.api.v2.pipe;

/** Stages at which the native fluid flow can be restricted without transferring ownership of its buffers. */
public enum FluidMovementPhase {
    /** Fluid offered by an external handler to an input face. */
    INSERT,
    /** Available input sections, before the native fair-share allocation. */
    BEFORE_CENTRE,
    /** Allocated liquid about to leave an input section and enter the centre. */
    TO_CENTRE,
    /** Liquid about to leave the centre for an output section. */
    FROM_CENTRE,
    /** Liquid about to leave an output section for a neighbouring handler. */
    OUTPUT,
    /** Fluid transferring directly to another loaded BuildCraft pipe through the resource service. */
    PIPE_TRANSFER
}
