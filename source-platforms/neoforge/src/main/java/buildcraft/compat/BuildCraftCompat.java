package buildcraft.compat;

import java.lang.reflect.InvocationTargetException;

import buildcraft.lib.internal.debug.BCLog;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

/** Optional cross-mod integration entrypoint. */
@Mod(BuildCraftCompat.MODID)
public final class BuildCraftCompat {
    public static final String MODID = "buildcraftcompat";

    public BuildCraftCompat(IEventBus modBus) {
        if (ModList.get().isLoaded("forestry")) {
            invokeOptionalRegistration("buildcraft.compat.forestry.ForestryCompat", modBus);
        }
        if (ModList.get().isLoaded("create")) {
            invokeOptionalRegistration("buildcraft.compat.create.CreateCompat", modBus);
        }
    }

    private static void invokeOptionalRegistration(String className, IEventBus modBus) {
        try {
            Class.forName(className).getMethod("register", IEventBus.class).invoke(null, modBus);
        } catch (ClassNotFoundException ignored) {
            // This target intentionally omits integrations whose dependency is unavailable for the selected NeoForge target.
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            BCLog.logger.error("Failed to initialize optional BuildCraft compatibility class {}", className, exception);
        }
    }
}
