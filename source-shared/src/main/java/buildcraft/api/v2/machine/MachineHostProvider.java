package buildcraft.api.v2.machine;

/** Addon block entities implement this so MachineService and gates can discover their active host. */
public interface MachineHostProvider {
    MachineHost machineHost();
}
