package buildcraft.compat.forestry;

import buildcraft.lib.platform.registry.RegistryBinding;
import java.lang.reflect.InvocationTargetException;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.compat.forestry.pipe.ForestryPipes;
import buildcraft.compat.forestry.pipe.ForestryPropolisNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** Forestry Community Edition integration. Loaded only when Forestry is present. */
public final class ForestryCompat {
    private static boolean registered;
    private static boolean runtimeInitialised;

    private ForestryCompat() {
    }

    /** Called during buildcraftcompat construction, while registries are still open. */
    public static synchronized void register(IEventBus modBus) {
        if (registered) {
            return;
        }
        registered = true;
        ForestryPipes.register(RegistryBinding.on(modBus));
        ForestryPropolisNetwork.register();

        // Keep every client-only class out of the dedicated-server class path while still
        // registering the model-bake listener before the first model reload.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registerClientEvents(modBus);
        }
    }

    private static void registerClientEvents(IEventBus modBus) {
        try {
            Class.forName("buildcraft.compat.forestry.pipe.client.ForestryCompatClient")
                .getMethod("register", IEventBus.class)
                .invoke(null, modBus);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                | InvocationTargetException | LinkageError e) {
            BCLog.logger.error("Failed to register Forestry compatibility client events", e);
        }
    }

    /** Late runtime hooks retained for BCLib's optional compatibility bootstrap. */
    public static synchronized void init() {
        if (!runtimeInitialised) {
            runtimeInitialised = true;
            BuildCraftApi.registry(BuildCraftRegistries.LIST_MATCH_ADAPTERS).register(
                new net.minecraft.resources.ResourceLocation("buildcraftcompat", "list_match/forestry"),
                new ForestryListMatchHandler()
            );
        }
    }
}
