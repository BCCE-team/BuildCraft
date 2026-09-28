package buildcraft.robotics;

import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import net.minecraft.world.item.CreativeModeTab;

/** Loader-neutral legacy Robotics facade used by unified-loader targets. */
public final class BCRobotics {
    public static final String MODID = LegacyRoboticsModule.MODID;
    public static final CreativeTabBC TAB_ROBOTICS = LegacyRoboticsModule.TAB_ROBOTICS;
    public static final BCRegistryEntry<CreativeModeTab> ROBOTICS_TAB = LegacyRoboticsModule.ROBOTICS_TAB;

    private BCRobotics() {
    }
}
