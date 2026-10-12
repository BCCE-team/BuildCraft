package buildcraft.api.v2.pipe;

/**
 * Receives one server-thread callback after the built-in fluid or energy flow tick.
 * An addon may inspect its pipe and initiate transfers via PipeResourceService.
 * Callback failures are isolated from other registered components.
 */
public interface PipeResourceTickComponent extends PipeComponent {
    void onResourceTick(PipeResourceTickContext context);
}
