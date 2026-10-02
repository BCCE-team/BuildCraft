package buildcraft.transport.api2;

import buildcraft.transport.internal.pluggable.PluggableDefinition;
import net.minecraft.resources.Identifier;

/** Verification-only gameplay bridge; not included in the production JAR. */
public final class PipeAttachmentBridge {
    private PipeAttachmentBridge() {}
    public static void ensureRegistered(Identifier id, PluggableDefinition definition) {}
}
