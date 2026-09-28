package buildcraft.silicon;

import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import net.minecraft.world.item.CreativeModeTab;

/** Loader-neutral legacy Silicon facade used by unified-loader targets. */
public final class BCSilicon {
    public static final String MODID = LegacySiliconModule.MODID;
    public static final CreativeTabBC tabPlugs = LegacySiliconModule.TAB_PLUGS;
    public static final CreativeTabBC tabFacades = LegacySiliconModule.TAB_FACADES;
    public static final BCRegistryEntry<CreativeModeTab> FACADES_TAB = LegacySiliconModule.FACADES_TAB;

    private BCSilicon() {
    }
}
