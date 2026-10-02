package buildcraft.transport.internal.pluggable;

import net.minecraft.resources.Identifier;

/** Verification-only gameplay bridge; not included in the production JAR. */
public interface IPluggableRegistry {
    void register(Identifier id, PluggableDefinition definition);
    PluggableDefinition getDefinition(Identifier id);
}
