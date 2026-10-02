package buildcraft.transport.internal.pluggable;

import net.minecraft.core.Direction;

/** Verification-only gameplay bridge; not included in the production JAR. */
public class PluggableDefinition {
    public final IPluggableCreator creator;
    public PluggableDefinition(IPluggableCreator creator) { this.creator = creator; }
    public interface IPluggableCreator {
        PipePluggable createSimplePluggable(PluggableDefinition definition, Object holder, Direction side);
    }
}
