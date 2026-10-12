package buildcraft.api.v2.machine;

@FunctionalInterface
public interface MachineComponentFactory<C extends MachineComponent> {
    /** Creates an unbound component. A decoded component receives the same lifecycle attachment as a new instance. */
    C create(MachineExecutionContext machine);
}
