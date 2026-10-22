package buildcraft.api.v2.machine;

/** Server-only machine behaviour. The host skips execution while control mode is OFF. */
@FunctionalInterface
public interface MachineTickComponent {
    void tick(MachineExecutionContext machine);
}
