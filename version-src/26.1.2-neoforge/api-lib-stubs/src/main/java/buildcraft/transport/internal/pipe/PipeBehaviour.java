package buildcraft.transport.internal.pipe;

import net.minecraft.core.Direction;

/** Verification-only gameplay bridge; not included in the production JAR. */
public class PipeBehaviour {
    public Object getCapability(Object capability, Direction side) { return null; }
}
