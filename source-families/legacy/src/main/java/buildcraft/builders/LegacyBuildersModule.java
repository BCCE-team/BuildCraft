package buildcraft.builders;

import buildcraft.builders.snapshot.RulesLoader;
import buildcraft.core.LegacyCoreModule;
import buildcraft.lib.internal.module.BCModuleBootstrapContext;
import buildcraft.lib.net.LegacyNetworkCatalog;

/** Loader-neutral ownership of the legacy Builders module bootstrap. */
public final class LegacyBuildersModule {
    public static final String MODID = "buildcraftbuilders";

    private LegacyBuildersModule() {
    }

    public static void bootstrap(BCModuleBootstrapContext context) {
        BCBuildersBlocks.registry(context.registries());
        BCBuildersItems.registry(context.registries());
        LegacyCoreModule.BUILDCRAFT_TAB.addItemProvider(BCBuildersItems::getCreativeTabItems);

        BCBuildersSchematics.preInit();
        BCBuildersConfig.preInit();
        BCBuildersRegistries.preInit();
        BCBuildersGuis.preInit(context.registries());
        context.registerConfig(
            MODID,
            BCBuildersConfig.config,
            BCBuildersConfig::onLoadConfig,
            BCBuildersConfig::onReloadConfig
        );
        LegacyNetworkCatalog.registerBuilders(context.messages());
        BCBuildersEventDist.registerGameplayEvents();
        BCBuildersStatements.preInit();
        context.onCommonSetup(LegacyBuildersModule::commonSetup);
    }

    private static void commonSetup() {
        BCBuildersConfig.reloadConfig(MODID);
        BCBuildersRegistries.init();
        RulesLoader.loadAll();
    }
}
