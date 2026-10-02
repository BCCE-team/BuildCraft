package buildcraft.transport.internal.pipe;

import buildcraft.transport.internal.IInjectable;
import buildcraft.transport.internal.pluggable.IPluggableRegistry;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;

/** Verification-only gameplay bridge; not included in the production JAR. */
public final class PipeApi {
    public static final BlockCapability<IInjectable, Direction> CAP_INJECTABLE = null;
    public static IPluggableRegistry pluggableRegistry;
    private PipeApi() {}
}
