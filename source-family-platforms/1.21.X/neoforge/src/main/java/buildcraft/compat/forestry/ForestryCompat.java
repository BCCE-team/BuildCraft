//? source if <1.21.11
package buildcraft.compat.forestry;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.compat.BuildCraftCompat;
import buildcraft.compat.forestry.pipe.ForestryPipes;
import buildcraft.compat.forestry.pipe.MessagePropolisFilterState;
import buildcraft.compat.forestry.pipe.MessagePropolisRuleChange;
import buildcraft.compat.forestry.pipe.MessagePropolisGenomeChange;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.platform.registry.RegistryBinding;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import java.lang.reflect.InvocationTargetException;

/** Optional Forestry CE 3.0 integration for Minecraft 1.21.1. */
public final class ForestryCompat {
    private static boolean registered;
    private static boolean initialized;

    private ForestryCompat() {}

    public static synchronized void register(IEventBus bus) {
        if (registered) return;
        registered = true;
        ForestryPipes.register(RegistryBinding.on(bus));
        // Do not expose ForestryCapabilities.FILTER_LOGIC on this pipe: Forestry's own
        // generic filter C2S handlers do not validate the open menu or player distance.
        // The BCCE messages below enforce both before modifying any saved filter.

        MessageManager.registerClientboundMessageClass(
            BCModules.COMPAT, MessagePropolisFilterState.class,
            MessagePropolisFilterState.HANDLER,
            MessagePropolisFilterState::toBytes,
            MessagePropolisFilterState::new
        );
        MessageManager.registerMessageClass(BCModules.COMPAT, MessagePropolisRuleChange.class,
            MessagePropolisRuleChange.HANDLER, MessagePropolisRuleChange::toBytes,
            MessagePropolisRuleChange::new, Dist.DEDICATED_SERVER);
        MessageManager.registerMessageClass(BCModules.COMPAT, MessagePropolisGenomeChange.class,
            MessagePropolisGenomeChange.HANDLER, MessagePropolisGenomeChange::toBytes,
            MessagePropolisGenomeChange::new, Dist.DEDICATED_SERVER);
        bus.addListener(ForestryCompat::onSetup);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registerClient(bus);
        }
    }

    private static void registerClient(IEventBus bus) {
        // Do not load client-only classes from a dedicated server.
        try {
            Class.forName("buildcraft.compat.forestry.pipe.client.ForestryCompatClient")
                .getMethod("register", IEventBus.class).invoke(null, bus);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException | LinkageError failure) {
            throw new IllegalStateException("Cannot register Forestry Apiarist's Pipe client", failure);
        }
    }

    private static synchronized void onSetup(FMLCommonSetupEvent event) {
        if (initialized) return;
        initialized = true;
        BuildCraftApi.registry(BuildCraftRegistries.LIST_MATCH_ADAPTERS).register(
            ResourceLocation.fromNamespaceAndPath(BuildCraftCompat.MODID, "list_match/forestry"),
            new ForestryListMatchHandler()
        );
    }
}
