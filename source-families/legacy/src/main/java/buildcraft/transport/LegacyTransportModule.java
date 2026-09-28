package buildcraft.transport;

import java.util.List;

import buildcraft.builders.internal.schematic.legacy.SchematicBlockFactoryRegistry;
import buildcraft.core.LegacyCoreModule;
import buildcraft.lib.BCLibRegistries;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.internal.module.BCModuleBootstrapContext;
import buildcraft.lib.net.LegacyNetworkCatalog;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.transport.api2.TransportApi2;
import buildcraft.transport.pipe.SchematicBlockPipe;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;

/** Loader-neutral ownership of the legacy Transport module bootstrap. */
public final class LegacyTransportModule {
    public static final String MODID = "buildcrafttransport";
    public static final CreativeTabBC TAB_PIPES =
        (CreativeTabBC) CreativeTabManager.createTab("buildcraft.pipes").setRecipeFolderName("pipes");
    public static final CreativeTabBC TAB_PLUGS =
        (CreativeTabBC) CreativeTabManager.createTab("buildcraft.plugs").setRecipeFolderName("plugs");

    private static final BCDeferredRegister<CreativeModeTab> CREATIVE_TABS =
        BCDeferredRegister.create("minecraft:creative_mode_tab", "buildcraft");
    public static final BCRegistryEntry<CreativeModeTab> PIPES_TAB = CREATIVE_TABS.register("pipes", () ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.buildcraft.pipes"))
            .icon(TAB_PIPES::makeIcon)
            .displayItems((parameters, output) -> TAB_PIPES.accept(List.of(), output::accept))
            .build());
    public static final BCRegistryEntry<CreativeModeTab> PLUGS_TAB = CREATIVE_TABS.register("plugs", () ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.buildcraft.plugs"))
            .icon(TAB_PLUGS::makeIcon)
            .displayItems((parameters, output) -> TAB_PLUGS.accept(List.of(), output::accept))
            .build());

    private LegacyTransportModule() {
    }

    public static void bootstrap(BCModuleBootstrapContext context) {
        BCLibRegistries.initApiRegistries();
        TransportApi2.install();
        BCTransportRegistries.preInit();
        BCTransportConfig.preInit();
        BCTransportPipes.preInit();
        BCTransportPlugs.preInit();
        BCTransportBlocks.registry(context.registries());
        BCTransportItems.registry(context.registries());
        TAB_PIPES.addItemProvider(BCTransportItems::getPipeTabItems);
        TAB_PLUGS.addItemProvider(BCTransportItems::getPlugTabItems);
        LegacyCoreModule.BUILDCRAFT_TAB.addItemProvider(BCTransportBlocks::getCreativeTabItems);
        BCTransportRecipes.preInit(context.registries());
        BCTransportGuis.preInit(context.registries());
        CREATIVE_TABS.register(context.registries());
        BCTransportStatements.preInit();

        context.registerConfig(
            MODID,
            BCTransportConfig.config,
            BCTransportConfig::onConfigLoad,
            BCTransportConfig::onConfigReload
        );
        LegacyNetworkCatalog.registerTransport(context.messages());
        BCTransportServerEvents.registerGameplayEvents();
        SchematicBlockFactoryRegistry.registerFactory(
            "pipe", 300, SchematicBlockPipe::predicate, SchematicBlockPipe::new
        );
        context.onCommonSetup(LegacyTransportModule::commonSetup);
    }

    private static void commonSetup() {
        BCTransportConfig.reloadConfig();
        BCTransportRegistries.init();
        TAB_PIPES.setItem(BCTransportItems.PIPE_ITEM_DIAMOND.get());
        TAB_PLUGS.setItem(BCTransportItems.plugBlocker.get());
    }
}
