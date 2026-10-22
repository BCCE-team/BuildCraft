package buildcraft.api.v2.machine;

@FunctionalInterface
public interface EngineFactory<E extends EngineComponent> {
    /** Instantiates a server-side engine component. Restored instances receive the normal machine lifecycle. */
    E create(EngineExecutionContext context);
}
