package buildcraft.transport;

import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import net.minecraft.world.item.CreativeModeTab;

/** Loader-neutral legacy Transport facade used by unified-loader targets. */
public final class BCTransport {
    public static final String MODID = LegacyTransportModule.MODID;
    public static final CreativeTabBC tabPipes = LegacyTransportModule.TAB_PIPES;
    public static final CreativeTabBC tabPlugs = LegacyTransportModule.TAB_PLUGS;
    public static final BCRegistryEntry<CreativeModeTab> PIPES_TAB = LegacyTransportModule.PIPES_TAB;
    public static final BCRegistryEntry<CreativeModeTab> PLUGS_TAB = LegacyTransportModule.PLUGS_TAB;

    private BCTransport() {
    }
}
