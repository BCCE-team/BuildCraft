package buildcraft.transport.pipe;

import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.PipeBehaviour;

/** Verification-only gameplay bridge; not included in the production JAR. */
public final class Pipe implements IPipe {
    public static final Pipe EMPTY = new Pipe();
    private static final PipeBehaviour BEHAVIOUR = new PipeBehaviour();
    public PipeBehaviour getBehaviour() { return BEHAVIOUR; }
}
