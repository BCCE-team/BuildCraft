package buildcraft.api.v2.machine;

/** An executable component may advertise unfinished work independently of the current progress state. */
@FunctionalInterface
public interface MachineWorkComponent {
    /** Called on the owning level thread; must not mutate the machine. */
    boolean hasWork(MachineExecutionContext machine);
}
