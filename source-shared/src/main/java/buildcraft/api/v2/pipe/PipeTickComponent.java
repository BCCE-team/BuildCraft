package buildcraft.api.v2.pipe;

public interface PipeTickComponent extends PipeComponent {
    /** Legacy API2 callback kept for source/binary compatibility. */
    default void tick(PipeMutationContext context) {
    }

    /** Preferred runtime callback with world and neighbour access. */
    default void tick(PipeExecutionContext context) {
        tick((PipeMutationContext) context);
    }
}
