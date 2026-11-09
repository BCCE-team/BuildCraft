package buildcraft.compat;

import java.lang.reflect.InvocationTargetException;

import buildcraft.lib.internal.debug.BCLog;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Optional cross-mod content. Gameplay blocks and items that only make sense with
 * another mod belong to this module rather than the base BuildCraft modules.
 */
@Mod(BuildCraftCompat.MODID)
public final class BuildCraftCompat {
    public static final String MODID = "buildcraftcompat";

    public BuildCraftCompat() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        if (ModList.get().isLoaded("forestry")) {
            invokeOptionalRegistration("buildcraft.compat.forestry.ForestryCompat", modBus);
        }
    }

    private static void invokeOptionalRegistration(String className, IEventBus modBus) {
        try {
            Class.forName(className).getMethod("register", IEventBus.class).invoke(null, modBus);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException
                | LinkageError e) {
            BCLog.logger.error("Failed to register optional compatibility content from {}", className, e);
        }
    }
}
