package buildcraft.api.v2.machine;

import buildcraft.api.v2.energy.MjPort;

/**
 * An addon block entity owns the normal MJ buffer and exposes it through its existing
 * energy capabilities. The host never creates a second energy inventory.
 *
 * <p>Save the buffer in the block entity's NBT alongside EngineHost.saveState(); restore both
 * before the first tick. EngineHost.mjPort exposes a read-only-to-callers producer view;
 * override mjPort or MjPortProvider only to expose deliberate additional side-specific roles.
 * The owner's existing block ticker calls host.tick(), its update packet uses fullSync/changedSync,
 * and its removal/unload callbacks forward to the matching host methods.
 */
public interface EngineHostOwner extends MachineHostOwner {
    /** A writable MJ storage/port with real SIMULATE and EXECUTE semantics. */
    MjPort outputBuffer();
}
