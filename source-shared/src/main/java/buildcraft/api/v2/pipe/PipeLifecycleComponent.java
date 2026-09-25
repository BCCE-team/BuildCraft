package buildcraft.api.v2.pipe;

/** Optional lifecycle callbacks for stateful pipe components. */
public interface PipeLifecycleComponent extends PipeComponent {
    default void onLoad(PipeExecutionContext context) {
    }

    default void onUnload(PipeExecutionContext context, PipeRemovalReason reason) {
    }

    default void onRemoved(PipeExecutionContext context, PipeRemovalReason reason) {
    }
}
