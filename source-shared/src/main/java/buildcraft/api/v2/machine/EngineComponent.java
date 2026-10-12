package buildcraft.api.v2.machine;

/** Executable engine logic; persistence and lifecycle use the normal machine component contracts. */
public interface EngineComponent extends MachineComponent, MachineTickComponent {
    /** Called once per server tick while the host is active and machine control is not OFF. */
    void tickEngine(EngineExecutionContext engine);

    @Override
    default void tick(MachineExecutionContext machine) {
        tickEngine((EngineExecutionContext) machine);
    }
}
