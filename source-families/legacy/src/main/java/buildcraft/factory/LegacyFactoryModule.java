package buildcraft.factory;

import buildcraft.core.LegacyCoreModule;
import buildcraft.lib.internal.module.BCModuleBootstrapContext;

/** Loader-neutral ownership of the legacy Factory module bootstrap. */
public final class LegacyFactoryModule {
    public static final String MODID = "buildcraftfactory";

    private LegacyFactoryModule() {
    }

    public static void bootstrap(BCModuleBootstrapContext context) {
        BCFactoryBlocks.registry(context.registries());
        BCFactoryItems.registry(context.registries());
        BCFactoryGuis.registry(context.registries());
        LegacyCoreModule.BUILDCRAFT_TAB.addItemProvider(BCFactoryItems::getCreativeTabItems);
    }
}
