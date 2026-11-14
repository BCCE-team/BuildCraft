package buildcraft.lib;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.module.ModuleInfo;
import buildcraft.lib.block.VanillaRotationHandlers;
import buildcraft.lib.expression.ExpressionDebugManager;
import buildcraft.lib.fluid.FabricFluidItemAdapter;
import buildcraft.lib.internal.api.v2.platform.PlatformApi2Bootstrap;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.mj.MjApi2PlatformBridge;
import buildcraft.lib.internal.statement.StatementManager;
import buildcraft.lib.list.VanillaListHandlers;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.misc.ExpressionCompat;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.net.BuildCraftTarget;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.net.cache.BuildCraftObjectCaches;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.recipe.BCLibIngredientTypes;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

/**
 * 26.3 Fabric equivalent of NeoForge BCLib's construction/common-setup phases.
 * All gameplay services and registries are the same BCCE classes.
 */
public final class BCLib {
    public static final String MODID = "buildcraftlib";
    public static final String VERSION = BuildCraftTarget.MOD_VERSION;
    public static final String MC_VERSION = BuildCraftTarget.MINECRAFT_VERSION;
    public static final String GIT_BRANCH = BuildCraftTarget.GIT_BRANCH;
    public static final String GIT_COMMIT_HASH = BuildCraftTarget.GIT_COMMIT_HASH;
    public static final String GIT_COMMIT_MSG = BuildCraftTarget.GIT_COMMIT_MESSAGE;
    public static final String GIT_COMMIT_AUTHOR = BuildCraftTarget.GIT_COMMIT_AUTHOR;
    public static final boolean DEV = FabricLoader.getInstance().isDevelopmentEnvironment()
        || Boolean.getBoolean("buildcraft.dev");

    private static boolean bootstrapped;
    private static boolean finished;
    private static boolean apiFinished;

    private BCLib() {}

    /** Call before initializing the other BCCE modules. */
    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        // NeoForge provides an active ModContainer for unqualified internal
        // registry names. Fabric must scope this explicitly to avoid changing
        // canonical resource IDs. Scoped context restores on all exit paths.
        buildcraft.lib.internal.core.BuildCraftAPI.withModule(MODID, BCLib::bootstrapInternal);
    }

    private static void bootstrapInternal() {
        bootstrapped = true;

        MjApi2PlatformBridge.install();
        PlatformApi2Bootstrap.install();

        BCLog.logger.info("Starting BuildCraft {} on Fabric {}",
            VERSION, MC_VERSION);
        if (!GIT_COMMIT_HASH.isBlank() && !"unknown".equals(GIT_COMMIT_HASH)) {
            BCLog.logger.info("Detailed Build Information: {} / {} / {}",
                GIT_BRANCH, GIT_COMMIT_HASH, GIT_COMMIT_MSG);
        }

        var moduleService = BuildCraftApi.service(BuildCraftServices.MODULES);
        BCLog.logger.info("Loaded Modules:");
        for (ModuleInfo module : moduleService.modules()) {
            if (module.loaded()) BCLog.logger.info("  - " + module.id().getPath());
        }
        BCLog.logger.info("Missing Modules:");
        for (ModuleInfo module : moduleService.modules()) {
            if (!module.loaded()) BCLog.logger.info("  - " + module.id().getPath());
        }

        buildcraft.lib.platform.config.ConfigBinding.installWatch();
        buildcraft.lib.platform.chunk.BCChunkTickets.init();
        BCLibItems.registry(RegistryBinding.direct());
        BCLibIngredientTypes.register();
        BCLibRegistries.fmlPreInit();
        BuildCraftApi.registry(BuildCraftRegistries.FLUID_ITEM_ADAPTERS).register(
            Identifier.fromNamespaceAndPath("buildcraft", "fabric_fluid_container"),
            new FabricFluidItemAdapter());
        StatementManager.setRegistryProvider(ItemStackUtil::requireActiveRegistryProvider);

        // The generic network codec is registered by Fabric's common entrypoint;
        // the class-specific handlers are provided by the same BCCE message catalog.
        BCLibProxy.MessageRegistry();
        ExpressionDebugManager.logger = BCLog.logger::info;
        ExpressionCompat.setup();
        BuildCraftObjectCaches.fmlPreInit();
        BCLibEventDist.registerGameplayEvents();

        BCLibRegistries.fmlInit();
        VanillaListHandlers.fmlInit();
        VanillaRotationHandlers.fmlInit();
    }

    /**
     * Call only after all modules have registered their API v2 extensions and
     * packets; freezing in bootstrap would break optional addons.
     */
    public static synchronized void finishModuleRegistration() {
        if (!bootstrapped) throw new IllegalStateException("BCLib.bootstrap was not called");
        if (finished) return;
        buildcraft.lib.platform.storage.FabricStorageLookups.install();
        finished = true;
        MarkerCache.postInit();
        BuildCraftObjectCaches.fmlPostInit();
        MessageManager.fmlPostInit();
    }

    /**
     * Seal API registries only after Fabric has run every addon ModInitializer.
     * The common entrypoint finalizes gameplay modules earlier, but Fabric loads
     * dependant mod entrypoints after ours, so freezing inside onInitialize()
     * would make ordinary addon registration impossible.
     */
    public static synchronized void finishApiRegistration() {
        if (!finished) throw new IllegalStateException("Gameplay module registration is not complete");
        if (apiFinished) return;
        BCLibRegistries.fmlPostInit();
        apiFinished = true;
    }

    public static Error throwBadClass(Error e, Class<?> cls) throws Error {
        throw new Error("Bad " + cls + " loaded from " + cls.getClassLoader()
            + " domain: " + cls.getProtectionDomain(), e);
    }
}
