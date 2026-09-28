package buildcraft.lib;

import buildcraft.lib.block.VanillaRotationHandlers;
import buildcraft.lib.chunkload.ChunkLoaderManager;
import buildcraft.lib.expression.ExpressionDebugManager;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.module.BCModuleBootstrapContext;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.list.VanillaListHandlers;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.misc.ExpressionCompat;
import buildcraft.lib.net.BuildCraftTarget;
import buildcraft.lib.net.LegacyNetworkCatalog;
import buildcraft.lib.net.cache.BuildCraftObjectCaches;
import buildcraft.lib.platform.runtime.PlatformRuntime;

/** Loader-neutral ownership of the legacy BCLib module bootstrap. */
public final class LegacyLibModule {
    public static final String MODID = "buildcraftlib";

    private LegacyLibModule() {
    }

    public static void bootstrap(BCModuleBootstrapContext context) {
        BCLibItems.registry(context.registries());
        BCLibRegistries.fmlPreInit();
        LegacyNetworkCatalog.registerLibrary(context.messages());

        ExpressionDebugManager.logger = BCLog.logger::info;
        ExpressionCompat.setup();
        BuildCraftObjectCaches.fmlPreInit();
        BCLibServerEvents.registerGameplayEvents();

        context.onCommonSetup(() -> {
            ChunkLoaderManager.init();
            BCLibRegistries.fmlInit();
            VanillaListHandlers.fmlInit();
            VanillaRotationHandlers.fmlInit();
        });
        context.onLoadComplete(() -> {
            initOptionalCompat("ic2", "buildcraft.compat.ic2.Ic2Compat");
            initOptionalCompat("forestry", "buildcraft.compat.forestry.ForestryCompat");
            MarkerCache.postInit();
            BuildCraftObjectCaches.fmlPostInit();
            context.networkPostInit();
            BCLibRegistries.fmlPostInit();
        });
    }

    public static void logStartup() {
        BCLog.logger.info("");
        BCLog.logger.info("Starting BuildCraft " + BuildCraftTarget.MOD_VERSION);
        BCLog.logger.info("Copyright (c) the BuildCraft team, 2011-2018");
        BCLog.logger.info("https://www.mod-buildcraft.com");
        if (!BuildCraftTarget.GIT_COMMIT_HASH.isBlank() && !"unknown".equals(BuildCraftTarget.GIT_COMMIT_HASH)) {
            BCLog.logger.info("Detailed Build Information:");
            BCLog.logger.info("  Branch " + BuildCraftTarget.GIT_BRANCH);
            BCLog.logger.info("  Commit " + BuildCraftTarget.GIT_COMMIT_HASH);
            BCLog.logger.info("    " + BuildCraftTarget.GIT_COMMIT_MESSAGE);
            BCLog.logger.info("    committed by " + BuildCraftTarget.GIT_COMMIT_AUTHOR);
        }
        BCLog.logger.info("");
    }

    private static void initOptionalCompat(String modId, String className) {
        if (!PlatformRuntime.isModLoaded(modId)) {
            return;
        }
        try {
            Class.forName(className).getMethod("init").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            BCLog.logger.error("Failed to initialise optional compatibility for {}", modId, e);
        }
    }
}
