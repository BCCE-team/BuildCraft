package buildcraft.api.v2.machine;

/** Lets engine discovery, machine controls and gates resolve the same running host. */
public interface EngineHostProvider extends MachineHostProvider {
    EngineHost engineHost();
    @Override default MachineHost machineHost() { return engineHost(); }
}
